package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.dto.LineDetailDto.Brigade;
import com.transitanalytics.dto.LineDetailDto.HourStat;
import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.dto.LineDetailDto.Variant;
import com.transitanalytics.dto.LineDetailDto.Zone2;
import com.transitanalytics.recording.PositionHistoryService;
import com.transitanalytics.repository.RouteRepository;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One line in detail. From the regular weekday timetable: its variants, lengths, scheduled travel times and speeds,
 * the part outside the city (zone 2) and the brigades (bus duties). From the recorded vehicle positions (weekdays):
 * travel time and speed of full runs by hour, average delay over the day and speed between stops in peak hours.
 */
@Service
public class LineDetailService {

    /** A gap between two runs of a brigade of at least this long is a break, not just a layover at the terminus. */
    static final int BREAK_SECONDS = 30 * 60;
    /** A variant counts as longer than the base route when it is this much longer. */
    static final double LONGER_MARGIN_KM = 0.2;
    /** Positions of one vehicle further apart than this don't belong to the same run. */
    static final int MAX_POLL_GAP_SECONDS = 180;
    /** Half the polling interval: how long after its last report a bus is assumed to arrive when no report follows. */
    static final int HALF_POLL_SECONDS = 30;
    /** A service day runs from 03:00 to 03:00; times before 03:00 count as hours 24, 25 and 26 of the day before. */
    static final int DAY_START_HOUR = 3;
    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private static final Pattern STOP_CODE = Pattern.compile("\\s*\\(\\d+\\)\\s*$");

    /** A timetable trip of the line. Times are seconds after midnight. */
    record TripRow(long id, String duty, int direction, String shapeId, String headsign, int start, int end,
                   Double km, int stops) {
        int duration() {
            return end - start;
        }
    }

    /** One position report: time in epoch seconds, seq = index of the next stop (0 = waiting at the first stop). */
    record Sample(long vehicle, double t, String brigade, String headsign, int seq) {
    }

    /**
     * Consecutive reports of one bus on one run.
     *
     * @param after time of the bus's next report after the run, null when there is none within a few minutes
     */
    record Track(String headsign, List<Sample> samples, Double after) {
    }

    /** A run of the base route recorded from its first to its last stop; times[k] = when the bus left stop k. */
    record FullRun(int direction, double[] times) {
        double departure() {
            return times[0];
        }

        double minutes() {
            return (times[times.length - 1] - times[0]) / 60;
        }
    }

    private record Timetable(String service, List<TripRow> trips, List<Variant> variants) {
        Map<Integer, Variant> mains() {
            Map<Integer, Variant> mains = new TreeMap<>();
            variants.stream().filter(v -> v.kind().equals("main")).forEach(v -> mains.put(v.direction(), v));
            return mains;
        }
    }

    private final JdbcTemplate jdbc;
    private final RouteRepository routes;
    private final LineStatsService lineStats;

    public LineDetailService(JdbcTemplate jdbc, RouteRepository routes, LineStatsService lineStats) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.lineStats = lineStats;
    }

    /** All lines, numbers first in numeric order. */
    @Transactional(readOnly = true)
    public List<LineDetailDto.LineRef> lines() {
        return routes.findAll().stream()
                .map(r -> new LineDetailDto.LineRef(r.getId(), r.getGtfsId(), r.displayName(), r.getSubtype()))
                .sorted(Comparator.comparing(LineDetailDto.LineRef::name, LineStatsService.LINE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public LineDetailDto.Detail detail(String line) {
        Route route = route(line);
        Timetable tt = timetable(route, true);
        List<TripRow> mainTrips = mainTrips(tt);
        Map<Integer, Variant> mains = tt.mains();

        Double length = mains.isEmpty() ? null
                : LineStatsService.round(mains.values().stream().mapToDouble(Variant::km).average().orElse(0), 1);
        Integer shortest = mainTrips.stream().map(t -> t.duration() / 60).min(Integer::compare).orElse(null);
        Integer longest = mainTrips.stream().map(t -> t.duration() / 60).max(Integer::compare).orElse(null);
        List<Double> speeds = mainTrips.stream().filter(t -> t.km() != null && t.duration() > 0)
                .map(t -> t.km() / (t.duration() / 3600.0)).toList();
        var base = new LineDetailDto.Base(length, shortest, longest,
                speeds.stream().min(Double::compare).map(v -> LineStatsService.round(v, 1)).orElse(null),
                speeds.stream().max(Double::compare).map(v -> LineStatsService.round(v, 1)).orElse(null));

        List<Zone2> zone2 = mains.values().stream()
                .map(v -> zone2(v.direction(), v.headsign(), v.stops())).filter(Objects::nonNull).toList();

        Map<String, String> kinds = new HashMap<>();
        tt.variants().forEach(v -> kinds.put(variantKey(v.direction(), v.shapeId()), v.kind()));
        return new LineDetailDto.Detail(route.getId(), route.getGtfsId(), route.displayName(), route.getSubtype(),
                tt.service(), base, tt.variants(), zone2, brigades(tt.trips(), kinds));
    }

    @Transactional(readOnly = true)
    public LineDetailDto.Performance performance(String line) {
        Route route = route(line);
        Timetable tt = timetable(route, false);
        Map<Integer, Variant> mains = tt.mains();

        // Weekday service days: a report at 00:30 on Saturday still belongs to Friday's service.
        String weekday = "extract(isodow FROM (p.observed_at AT TIME ZONE '%s') - make_interval(hours => %d)) <= 5"
                .formatted(ZONE.getId(), DAY_START_HOUR);
        String ofLine = "p.route_name = ? AND p.vehicle_id IN (SELECT id FROM vehicle WHERE agency = ?) AND " + weekday;

        List<Sample> samples = jdbc.query("""
                SELECT p.vehicle_id, extract(epoch FROM p.observed_at) AS t, p.brigade, p.headsign,
                       p.next_stop_sequence AS seq
                FROM position_sample p
                WHERE p.next_stop_sequence IS NOT NULL AND""" + " " + ofLine + " ORDER BY p.vehicle_id, p.observed_at",
                (rs, i) -> new Sample(rs.getLong("vehicle_id"), rs.getDouble("t"), rs.getString("brigade"),
                        rs.getString("headsign"), rs.getInt("seq")),
                route.getGtfsId(), ZbiorkomBialystokService.CITY_AGENCY);

        // Shortest and longest scheduled trip of the base route per direction, to drop runs that were measured wrong.
        Map<Integer, int[]> scheduled = new HashMap<>();
        for (TripRow t : mainTrips(tt)) {
            scheduled.merge(t.direction(), new int[]{t.duration(), t.duration()},
                    (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        List<FullRun> runs = new ArrayList<>();
        for (Track track : splitRuns(samples)) {
            for (Variant main : mains.values()) {
                if (!Objects.equals(track.headsign(), main.headsign())) {
                    continue;
                }
                double[] times = passTimes(track, kms(main));
                int[] range = scheduled.get(main.direction());
                if (times != null && range != null) {
                    double seconds = times[times.length - 1] - times[0];
                    if (seconds >= range[0] * 0.5 && seconds <= range[1] * 2.0) {
                        runs.add(new FullRun(main.direction(), times));
                        break;
                    }
                }
            }
        }

        Map<Integer, List<Double>> minutesByHour = new TreeMap<>();
        Map<Integer, List<Double>> speedByHour = new TreeMap<>();
        for (FullRun run : runs) {
            int hour = serviceHour(run.departure());
            minutesByHour.computeIfAbsent(hour, h -> new ArrayList<>()).add(run.minutes());
            speedByHour.computeIfAbsent(hour, h -> new ArrayList<>())
                    .add(mains.get(run.direction()).km() / (run.minutes() / 60));
        }

        List<LineDetailDto.DelaySlot> delays = jdbc.query("""
                SELECT (extract(epoch FROM ((p.observed_at AT TIME ZONE '%1$s') - make_interval(hours => %2$d))::time)
                        ::int / 900) * 15 + %2$d * 60 AS minute,
                       avg(p.delay_seconds) AS delay, count(*) AS samples
                FROM position_sample p
                WHERE p.delay_seconds IS NOT NULL AND p.delay_seconds <= %3$d
                  AND coalesce(p.next_stop_sequence, 1) > 0 AND""".formatted(ZONE.getId(), DAY_START_HOUR,
                        PositionHistoryService.OUTLIER_DELAY_SECONDS) + " " + ofLine + " GROUP BY 1 ORDER BY 1",
                (rs, i) -> new LineDetailDto.DelaySlot(rs.getInt("minute"),
                        LineStatsService.round(rs.getDouble("delay"), 0), rs.getLong("samples")),
                route.getGtfsId(), ZbiorkomBialystokService.CITY_AGENCY);

        TreeSet<LocalDate> days = new TreeSet<>();
        samples.forEach(s -> days.add(serviceDay(s.t())));

        List<LineDetailDto.SegmentProfile> segments = mains.values().stream()
                .map(main -> segmentProfile(main, runs.stream()
                        .filter(r -> r.direction() == main.direction() && inPeak(r.departure())).toList()))
                .toList();

        return new LineDetailDto.Performance(days.isEmpty() ? null : days.first(),
                days.isEmpty() ? null : days.last(), days.size(), runs.size(),
                minutesByHour.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 0)).toList(),
                speedByHour.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 1)).toList(),
                delays, segments);
    }

    // --- timetable ---------------------------------------------------------------------------------------------

    /** @param line the route's GTFS id, e.g. "103" */
    private Route route(String line) {
        return jdbc.queryForList("SELECT id FROM route WHERE gtfs_id = ?", Long.class, line).stream().findFirst()
                .flatMap(routes::findById).orElseThrow(() -> new NotFoundException("Line", line));
    }

    private Timetable timetable(Route route, boolean withShapes) {
        String service = lineStats.weekdayService();
        if (service == null) {
            return new Timetable(null, List.of(), List.of());
        }
        // The brigade (vehicle duty) is the trip id's prefix in this feed (e.g. 103-01_P_1349_…), as in Line statistics.
        List<TripRow> trips = jdbc.query("""
                SELECT t.id, coalesce(t.block_id, split_part(t.gtfs_id, '_', 1)) AS duty,
                       coalesce(t.direction, 0) AS direction, t.shape_id, t.headsign,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       max(st.shape_dist_traveled) AS km, count(*) AS stops
                FROM trip t JOIN stop_time st ON st.trip_id = t.id
                WHERE t.route_id = ? AND t.service_id = ?
                GROUP BY t.id
                ORDER BY t.id""",
                (rs, i) -> new TripRow(rs.getLong("id"), rs.getString("duty"), rs.getInt("direction"),
                        rs.getString("shape_id"), rs.getString("headsign"), rs.getInt("start_s"), rs.getInt("end_s"),
                        rs.getObject("km") == null ? null : rs.getDouble("km"), rs.getInt("stops")),
                route.getId(), service);

        // Variants: one per direction and shape. The base route of a direction is its most frequent shape.
        Map<String, List<TripRow>> byVariant = trips.stream().collect(Collectors.groupingBy(
                t -> variantKey(t.direction(), t.shapeId()), LinkedHashMap::new, Collectors.toList()));
        Map<Integer, String> mainShape = new HashMap<>();
        trips.stream().collect(Collectors.groupingBy(TripRow::direction)).forEach((dir, list) ->
                mainShape.put(dir, LineStatsService.mostFrequent(list.stream().map(TripRow::shapeId).toList())));

        // Stops of each variant from its trip with the most stops.
        Map<String, TripRow> representative = new LinkedHashMap<>();
        byVariant.forEach((key, list) -> representative.put(key, list.stream()
                .max(Comparator.comparingInt(TripRow::stops).thenComparing(TripRow::id, Comparator.reverseOrder()))
                .orElseThrow()));
        Map<Long, List<StopPoint>> stopsByTrip = stops(representative.values().stream().map(TripRow::id).toList());
        Map<String, List<double[]>> shapes = withShapes
                ? shapes(byVariant.values().stream().map(l -> l.get(0).shapeId()).filter(Objects::nonNull).toList())
                : Map.of();

        Map<Integer, Double> mainKm = new HashMap<>();
        byVariant.forEach((key, list) -> {
            TripRow first = list.get(0);
            if (Objects.equals(first.shapeId(), mainShape.get(first.direction()))) {
                mainKm.put(first.direction(), maxKm(list));
            }
        });

        List<Variant> variants = new ArrayList<>();
        byVariant.forEach((key, list) -> {
            TripRow first = list.get(0);
            double km = maxKm(list);
            String kind = Objects.equals(first.shapeId(), mainShape.get(first.direction())) ? "main"
                    : km > mainKm.getOrDefault(first.direction(), 0.0) + LONGER_MARGIN_KM ? "longer" : "shorter";
            List<StopPoint> stops = stopsByTrip.getOrDefault(representative.get(key).id(), List.of());
            List<double[]> points = !withShapes ? List.of()
                    : shapes.containsKey(first.shapeId()) ? shapes.get(first.shapeId())
                    : stops.stream().filter(s -> s.lat() != null && s.lon() != null)
                            .map(s -> new double[]{s.lat(), s.lon()}).toList();
            variants.add(new Variant(first.direction(), first.shapeId(),
                    LineStatsService.mostFrequent(list.stream().map(TripRow::headsign).toList()), kind,
                    LineStatsService.round(km, 2), list.size(),
                    list.stream().mapToInt(t -> t.duration() / 60).min().orElse(0),
                    list.stream().mapToInt(t -> t.duration() / 60).max().orElse(0),
                    stops.isEmpty() ? null : stops.get(0).name(),
                    stops.isEmpty() ? null : stops.get(stops.size() - 1).name(), points, stops));
        });
        variants.sort(Comparator.comparingInt(Variant::direction)
                .thenComparing(v -> !v.kind().equals("main"))
                .thenComparing(Variant::km, Comparator.reverseOrder()));
        return new Timetable(service, trips, variants);
    }

    private static List<TripRow> mainTrips(Timetable tt) {
        Map<Integer, Variant> mains = tt.mains();
        return tt.trips().stream()
                .filter(t -> mains.containsKey(t.direction())
                        && Objects.equals(mains.get(t.direction()).shapeId(), t.shapeId()))
                .toList();
    }

    private Map<Long, List<StopPoint>> stops(List<Long> tripIds) {
        Map<Long, List<StopPoint>> result = new HashMap<>();
        if (tripIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT st.trip_id, s.name, s.latitude, s.longitude, st.shape_dist_traveled, s.zone_id
                FROM stop_time st JOIN stop s ON s.id = st.stop_id
                WHERE st.trip_id IN (%s)
                ORDER BY st.trip_id, st.stop_sequence""".formatted(placeholders(tripIds.size())),
                rs -> {
                    result.computeIfAbsent(rs.getLong("trip_id"), k -> new ArrayList<>()).add(new StopPoint(
                            stopName(rs.getString("name")),
                            rs.getObject("latitude") == null ? null : rs.getDouble("latitude"),
                            rs.getObject("longitude") == null ? null : rs.getDouble("longitude"),
                            LineStatsService.round(rs.getDouble("shape_dist_traveled"), 3), rs.getString("zone_id")));
                },
                tripIds.toArray());
        return result;
    }

    private Map<String, List<double[]>> shapes(List<String> shapeIds) {
        Map<String, List<double[]>> result = new HashMap<>();
        List<String> distinct = shapeIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT shape_id, latitude, longitude FROM shape_point
                WHERE shape_id IN (%s) ORDER BY shape_id, sequence""".formatted(placeholders(distinct.size())),
                rs -> {
                    result.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>()).add(new double[]{
                            LineStatsService.round(rs.getDouble("latitude"), 5),
                            LineStatsService.round(rs.getDouble("longitude"), 5)});
                },
                distinct.toArray());
        return result;
    }

    // --- pure helpers (unit tested) ------------------------------------------------------------------------------

    /** The stop name without the stop code the feed appends, e.g. "Lodowa/Chłodna(654)" → "Lodowa/Chłodna". */
    static String stopName(String name) {
        return name == null ? null : STOP_CODE.matcher(name).replaceFirst("");
    }

    /**
     * The part of a route in fare zone 2: its stops there, and its distance there. The zone border isn't in the feed,
     * so a segment between a zone-1 and a zone-2 stop counts half. Null when the route has no stop in zone 2.
     */
    static Zone2 zone2(int direction, String headsign, List<StopPoint> stops) {
        int count = (int) stops.stream().filter(LineDetailService::inZone2).count();
        if (count == 0) {
            return null;
        }
        double km = 0;
        for (int i = 1; i < stops.size(); i++) {
            double segment = stops.get(i).km() - stops.get(i - 1).km();
            int ends = (inZone2(stops.get(i - 1)) ? 1 : 0) + (inZone2(stops.get(i)) ? 1 : 0);
            km += segment * ends / 2.0;
        }
        double total = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).km() - stops.get(0).km();
        return new Zone2(direction, headsign, count, stops.size(), LineStatsService.round(km, 1),
                LineStatsService.round(total, 1));
    }

    private static boolean inZone2(StopPoint s) {
        return "2".equals(s.zone());
    }

    /**
     * The day's brigades: each bus's runs in order, from leaving the depot (first departure) to returning to it (last
     * arrival), with the gaps between runs of at least {@link #BREAK_SECONDS} as breaks.
     *
     * @param kinds variant kind by {@link #variantKey}
     */
    static List<Brigade> brigades(List<TripRow> trips, Map<String, String> kinds) {
        Map<String, List<TripRow>> byDuty = new TreeMap<>();
        trips.stream().filter(t -> t.duty() != null)
                .forEach(t -> byDuty.computeIfAbsent(t.duty(), k -> new ArrayList<>()).add(t));
        List<Brigade> brigades = new ArrayList<>();
        byDuty.forEach((duty, list) -> {
            list.sort(Comparator.comparingInt(TripRow::start).thenComparingInt(TripRow::end));
            List<LineDetailDto.Run> runs = list.stream().map(t -> new LineDetailDto.Run(t.start(), t.end(),
                    t.direction(), t.headsign(), kinds.getOrDefault(variantKey(t.direction(), t.shapeId()), "main")))
                    .toList();
            List<LineDetailDto.Break> breaks = new ArrayList<>();
            for (int i = 1; i < list.size(); i++) {
                int from = list.get(i - 1).end();
                int to = list.get(i).start();
                if (to - from >= BREAK_SECONDS) {
                    breaks.add(new LineDetailDto.Break(from, to));
                }
            }
            brigades.add(new Brigade(duty, list.get(0).start(),
                    list.stream().mapToInt(TripRow::end).max().orElseThrow(), runs, breaks));
        });
        return brigades;
    }

    static String variantKey(int direction, String shapeId) {
        return direction + "|" + shapeId;
    }

    /**
     * Splits reports (ordered by vehicle and time) into runs: a run ends when the bus's brigade or headsign changes,
     * its next stop goes back by more than two stops, or it doesn't report for {@link #MAX_POLL_GAP_SECONDS}. At a
     * terminus the feed switches to the next run's headsign, so the layover belongs to the next run.
     */
    static List<Track> splitRuns(List<Sample> samples) {
        List<Track> tracks = new ArrayList<>();
        List<Sample> current = new ArrayList<>();
        for (Sample s : samples) {
            if (!current.isEmpty()) {
                Sample last = current.get(current.size() - 1);
                boolean sameBus = last.vehicle() == s.vehicle() && s.t() - last.t() <= MAX_POLL_GAP_SECONDS;
                int highest = current.stream().mapToInt(Sample::seq).max().orElse(0);
                if (sameBus && Objects.equals(last.brigade(), s.brigade())
                        && Objects.equals(last.headsign(), s.headsign()) && s.seq() >= highest - 2) {
                    current.add(s);
                    continue;
                }
                tracks.add(new Track(current.get(0).headsign(), current, sameBus ? s.t() : null));
                current = new ArrayList<>();
            }
            current.add(s);
        }
        if (!current.isEmpty()) {
            tracks.add(new Track(current.get(0).headsign(), current, null));
        }
        return tracks;
    }

    /**
     * When the bus left each stop of a run (epoch seconds; the last entry is the arrival at the terminus), or null
     * unless the run covers the whole route: it starts waiting at the first stop and reaches the last one, no further
     * (or the last-but-one, when the bus's next report follows).
     *
     * <p>A report gives the next stop, so stops passed between two reports were passed somewhere in between: a single
     * stop at the midpoint (±30 s with one report a minute), several spread by their distance along the route. The
     * terminus is reached between the run's last report and the bus's next one, or half a poll after the last report
     * when none follows.
     *
     * @param km each stop's distance along the route
     */
    static double[] passTimes(Track track, double[] km) {
        int n = km.length;
        List<Sample> samples = track.samples();
        if (n < 2 || samples.isEmpty() || samples.get(0).seq() != 0) {
            return null;
        }
        // The feed often switches to the next run's headsign before it reports the terminus as the next stop, so a run
        // heading to the last-but-one stop counts too when the bus's next run follows straight after.
        int highest = samples.stream().mapToInt(Sample::seq).max().orElse(0);
        if (highest > n - 1 || highest < n - 2 || (highest == n - 2 && track.after() == null)) {
            return null;
        }
        double[] times = new double[n];
        int passed = 0; // stops [0, passed) have a time
        for (int i = 1; i < samples.size(); i++) {
            int next = Math.min(samples.get(i).seq(), n - 1);
            if (next > passed) {
                spread(times, km, passed, next, samples.get(i - 1).t(), samples.get(i).t());
                passed = next;
            }
        }
        double last = samples.get(samples.size() - 1).t();
        double end = track.after() != null ? track.after() : last + 2 * HALF_POLL_SECONDS;
        spread(times, km, passed, n, last, end);
        return times;
    }

    /** Places stops [from, to) between t0 and t1: one at the midpoint, several spread by distance. */
    static void spread(double[] times, double[] km, int from, int to, double t0, double t1) {
        int count = to - from;
        double span = km[to - 1] - km[from];
        if (count == 1 || span <= 0) {
            for (int j = from; j < to; j++) {
                times[j] = t0 + (t1 - t0) * (j - from + 0.5) / count;
            }
            return;
        }
        // Half the average stop spacing before the first and after the last stop, so the stops sit inside the gap.
        double pad = span / (count - 1);
        for (int j = from; j < to; j++) {
            times[j] = t0 + (t1 - t0) * (km[j] - km[from] + pad / 2) / (span + pad);
        }
    }

    /** Min, max, mean and median of the values, rounded to the decimals. */
    static HourStat hourStat(int hour, List<Double> values, int decimals) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
        return new HourStat(hour, n, LineStatsService.round(sorted.get(0), decimals),
                LineStatsService.round(sorted.get(n - 1), decimals), LineStatsService.round(mean, decimals),
                LineStatsService.round(median, decimals));
    }

    /**
     * Speed between consecutive stops of a route: total distance over total time of the runs, so the ±30 s error of
     * each pass time averages out over many runs.
     */
    static LineDetailDto.SegmentProfile segmentProfile(Variant main, List<FullRun> runs) {
        List<StopPoint> stops = main.stops();
        List<LineDetailDto.Segment> segments = new ArrayList<>();
        for (int k = 0; k + 1 < stops.size(); k++) {
            double km = stops.get(k + 1).km() - stops.get(k).km();
            double seconds = 0;
            for (FullRun run : runs) {
                seconds += run.times()[k + 1] - run.times()[k];
            }
            Double kmh = runs.isEmpty() || seconds <= 0 ? null
                    : LineStatsService.round(km * runs.size() / (seconds / 3600), 1);
            segments.add(new LineDetailDto.Segment(stops.get(k).name(), stops.get(k + 1).name(),
                    stops.get(k).km(), stops.get(k + 1).km(), kmh));
        }
        return new LineDetailDto.SegmentProfile(main.direction(), main.headsign(), runs.size(), segments);
    }

    private static double[] kms(Variant v) {
        return v.stops().stream().mapToDouble(StopPoint::km).toArray();
    }

    private static double maxKm(List<TripRow> trips) {
        return trips.stream().map(TripRow::km).filter(Objects::nonNull).mapToDouble(d -> d).max().orElse(0);
    }

    /** Hour of the service day: 3–23, then 24, 25, 26 for the hours after midnight. */
    static int serviceHour(double epochSeconds) {
        int hour = ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE).getHour();
        return hour < DAY_START_HOUR ? hour + 24 : hour;
    }

    static LocalDate serviceDay(double epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE)
                .minusHours(DAY_START_HOUR).toLocalDate();
    }

    /** Whether a run departing then starts in the peak hours of Line statistics. */
    static boolean inPeak(double epochSeconds) {
        int second = ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE).toLocalTime()
                .toSecondOfDay();
        return LineStatsService.PEAK.stream().anyMatch(w -> second >= w.from() && second < w.to());
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
