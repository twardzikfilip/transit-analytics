package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.dto.LineDetailDto.Brigade;
import com.transitanalytics.dto.LineDetailDto.HourStat;
import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.dto.LineDetailDto.Variant;
import com.transitanalytics.dto.LineDetailDto.Zone2;
import com.transitanalytics.recording.DelayOutliers;
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
                   Double km, int stops, String firstStop, String lastStop, StopRef first, StopRef last) {
        TripRow(long id, String duty, int direction, String shapeId, String headsign, int start, int end,
                Double km, int stops, String firstStop, String lastStop) {
            this(id, duty, direction, shapeId, headsign, start, end, km, stops, firstStop, lastStop,
                    new StopRef(firstStop, null, null), new StopRef(lastStop, null, null));
        }

        int duration() {
            return end - start;
        }
    }

    /**
     * One position report: time in epoch seconds, seq = index of the next stop (0 = waiting at the first stop) and
     * that stop's name.
     */
    record Sample(long vehicle, double t, String brigade, String headsign, int seq, String nextStop) {
        Sample(long vehicle, double t, String brigade, String headsign, int seq) {
            this(vehicle, t, brigade, headsign, seq, null);
        }
    }

    /**
     * Consecutive reports of one bus on one run.
     *
     * @param after time of the bus's next report after the run, null when there is none within a few minutes
     */
    record Track(String headsign, List<Sample> samples, Double after) {
    }

    /** A run recorded from its first to its last stop; times[k] = when the bus left stop k. */
    record FullRun(int direction, double[] times, Variant variant) {
        FullRun(int direction, double[] times) {
            this(direction, times, null);
        }

        double departure() {
            return times[0];
        }

        double minutes() {
            return (times[times.length - 1] - times[0]) / 60;
        }
    }

    /**
     * @param endStops standard end stops (termini), as places of nearby stops
     * @param depot    "pull_out" or "pull_in" by trip id, for depot runs
     */
    private record Timetable(String service, List<TripRow> trips, List<Variant> variants, List<Place> endStops,
                             Map<Long, String> depot) {
        Map<Integer, Variant> mains() {
            Map<Integer, Variant> mains = new TreeMap<>();
            variants.stream().filter(v -> v.kind().equals("main")).forEach(v -> mains.put(v.direction(), v));
            return mains;
        }
    }

    private final JdbcTemplate jdbc;
    private final RouteRepository routes;
    private final WeekdayTimetable weekdayTimetable;

    public LineDetailService(JdbcTemplate jdbc, RouteRepository routes, WeekdayTimetable weekdayTimetable) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.weekdayTimetable = weekdayTimetable;
    }

    /** All lines, numbers first in numeric order. */
    @Transactional(readOnly = true)
    public List<LineDetailDto.LineRef> lines() {
        return routes.findAll().stream()
                .map(r -> new LineDetailDto.LineRef(r.getId(), r.getGtfsId(), r.displayName(), r.getSubtype()))
                .sorted(Comparator.comparing(LineDetailDto.LineRef::name, TimetableMath.LINE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public LineDetailDto.Detail detail(String line) {
        Route route = route(line);
        Timetable tt = timetable(route, true);
        List<TripRow> mainTrips = mainTrips(tt);
        Map<Integer, Variant> mains = tt.mains();

        Double length = mains.isEmpty() ? null
                : TimetableMath.round(mains.values().stream().mapToDouble(Variant::km).average().orElse(0), 1);
        Integer shortest = mainTrips.stream().map(t -> t.duration() / 60).min(Integer::compare).orElse(null);
        Integer longest = mainTrips.stream().map(t -> t.duration() / 60).max(Integer::compare).orElse(null);
        List<Double> speeds = mainTrips.stream().filter(t -> t.km() != null && t.duration() > 0)
                .map(t -> t.km() / (t.duration() / 3600.0)).toList();
        var base = new LineDetailDto.Base(length, shortest, longest,
                speeds.stream().min(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                speeds.stream().max(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null));

        // Zone 2 on the base route and on longer variants (short turns and depot runs don't reach further).
        List<Zone2> zone2 = tt.variants().stream()
                .filter(v -> v.kind().equals("main") || v.kind().equals("longer"))
                .map(v -> zone2(v.direction(), v.headsign(), v.kind(), v.stops())).filter(Objects::nonNull).toList();

        Map<String, String> kinds = new HashMap<>();
        tt.variants().forEach(v -> kinds.put(variantKey(v.direction(), v.shapeId()), v.kind()));

        List<LineDetailDto.DepotRun> depotRuns = tt.trips().stream()
                .filter(t -> tt.depot().containsKey(t.id()))
                .sorted(Comparator.comparingInt(TripRow::start).thenComparing(TripRow::duty))
                .map(t -> new LineDetailDto.DepotRun(t.duty(), t.start(), t.end(), t.firstStop(), t.lastStop(),
                        t.direction(), t.headsign(), tt.depot().get(t.id()),
                        t.km() == null ? null : TimetableMath.round(t.km(), 1)))
                .toList();
        // Depot-run stops away from the end stops, grouped like them: arrivals (pull-ins) name a place.
        List<StopRef> depotArrivals = new ArrayList<>();
        List<StopRef> depotDepartures = new ArrayList<>();
        tt.trips().stream().filter(t -> tt.depot().containsKey(t.id())).forEach(t -> {
            if (tt.depot().get(t.id()).equals("pull_out")) {
                depotDepartures.add(t.first());
            } else {
                depotArrivals.add(t.last());
            }
        });
        List<Place> depotPlaces = places(
                depotArrivals.stream().filter(stop -> !atPlace(tt.endStops(), stop)).toList(),
                depotDepartures.stream().filter(stop -> !atPlace(tt.endStops(), stop)).toList());

        return new LineDetailDto.Detail(route.getId(), route.getGtfsId(), route.displayName(), route.getSubtype(),
                tt.service(), base, tt.variants(), zone2, brigades(tt.trips(), kinds, tt.depot()),
                toDto(tt.endStops()), toDto(depotPlaces), depotRuns);
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
                       p.next_stop_sequence AS seq, p.next_stop_name
                FROM position_sample p
                WHERE p.next_stop_sequence IS NOT NULL AND""" + " " + ofLine + " ORDER BY p.vehicle_id, p.observed_at",
                (rs, i) -> new Sample(rs.getLong("vehicle_id"), rs.getDouble("t"), rs.getString("brigade"),
                        rs.getString("headsign"), rs.getInt("seq"), rs.getString("next_stop_name")),
                route.getGtfsId(), ZbiorkomBialystokService.CITY_AGENCY);

        // Runs are measured per route pattern: the base route and longer variants, each in both directions (the
        // reverse may be classed differently, e.g. when the most frequent variant differs by direction).
        List<List<Variant>> patterns = patterns(tt.variants());
        List<Variant> measured = patterns.stream().flatMap(List::stream).toList();
        List<FullRun> runs = new ArrayList<>();
        for (Track track : splitRuns(samples)) {
            FullRun run = matchRun(track, measured);
            if (run != null) {
                runs.add(run);
            }
        }

        List<LineDetailDto.TravelTimes> travelTimes = new ArrayList<>();
        for (List<Variant> variants : patterns) {
            List<String> keys = variants.stream().map(v -> variantKey(v.direction(), v.shapeId())).toList();
            List<TripRow> trips = tt.trips().stream()
                    .filter(t -> keys.contains(variantKey(t.direction(), t.shapeId()))).toList();
            List<FullRun> groupRuns = runs.stream().filter(r -> variants.contains(r.variant())).toList();
            Variant first = variants.stream().filter(v -> v.direction() == 0).findFirst().orElse(variants.get(0));
            boolean base = variants.stream().anyMatch(v -> v.kind().equals("main"));
            travelTimes.add(travelTimes(base ? "main" : "longer", first.firstStop() + " ⇄ " + first.lastStop(),
                    variants, trips, groupRuns));
        }
        List<LineDetailDto.DelaySlot> delays = jdbc.query("""
                SELECT (extract(epoch FROM ((p.observed_at AT TIME ZONE '%1$s') - make_interval(hours => %2$d))::time)
                        ::int / 900) * 15 + %2$d * 60 AS minute,
                       avg(p.delay_seconds) AS delay, count(*) AS samples
                FROM position_sample p
                WHERE p.delay_seconds IS NOT NULL AND p.delay_seconds <= %3$d
                  AND coalesce(p.next_stop_sequence, 1) > 0 AND""".formatted(ZONE.getId(), DAY_START_HOUR,
                        DelayOutliers.THRESHOLD_SECONDS) + " " + ofLine + " GROUP BY 1 ORDER BY 1",
                (rs, i) -> new LineDetailDto.DelaySlot(rs.getInt("minute"),
                        TimetableMath.round(rs.getDouble("delay"), 0), rs.getLong("samples")),
                route.getGtfsId(), ZbiorkomBialystokService.CITY_AGENCY);

        TreeSet<LocalDate> days = new TreeSet<>();
        samples.forEach(s -> days.add(serviceDay(s.t())));

        List<LineDetailDto.SegmentProfile> segments = mains.values().stream()
                .map(main -> segmentProfile(main, runs.stream()
                        .filter(r -> r.variant() == main && inPeak(r.departure())).toList()))
                .toList();

        return new LineDetailDto.Performance(days.isEmpty() ? null : days.first(),
                days.isEmpty() ? null : days.last(), days.size(), runs.size(), travelTimes, delays, segments,
                vehicles(route.getGtfsId()));
    }

    /** Which buses served the line, from every recorded day of it. */
    private LineDetailDto.Vehicles vehicles(String line) {
        List<VehicleDay> rows = jdbc.query("""
                SELECT ((p.observed_at AT TIME ZONE '%s') - make_interval(hours => %d))::date AS day,
                       coalesce(p.brigade, '') AS brigade, v.vehicle_number, v.model,
                       min(p.observed_at) AS first_seen, max(p.observed_at) AS last_seen, count(*) AS reports
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE p.route_name = ? AND v.agency = ?
                GROUP BY 1, 2, 3, 4""".formatted(ZONE.getId(), DAY_START_HOUR),
                (rs, i) -> new VehicleDay(rs.getObject("day", LocalDate.class), rs.getString("brigade"),
                        rs.getString("vehicle_number"), rs.getString("model"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant(),
                        rs.getInt("reports")),
                line, ZbiorkomBialystokService.CITY_AGENCY);
        return vehicleUse(rows);
    }

    // --- timetable ---------------------------------------------------------------------------------------------

    /** @param line the route's GTFS id, e.g. "103" */
    private Route route(String line) {
        return jdbc.queryForList("SELECT id FROM route WHERE gtfs_id = ?", Long.class, line).stream().findFirst()
                .flatMap(routes::findById).orElseThrow(() -> new NotFoundException("Line", line));
    }

    private Timetable timetable(Route route, boolean withShapes) {
        String service = weekdayTimetable.serviceId();
        if (service == null) {
            return new Timetable(null, List.of(), List.of(), List.of(), Map.of());
        }
        // The brigade (vehicle duty) is the trip id's prefix in this feed (e.g. 103-01_P_1349_…), as in Line statistics.
        List<TripRow> trips = jdbc.query("""
                SELECT t.id, coalesce(t.block_id, split_part(t.gtfs_id, '_', 1)) AS duty,
                       coalesce(t.direction, 0) AS direction, t.shape_id, t.headsign,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       max(st.shape_dist_traveled) AS km, count(*) AS stops,
                       (array_agg(s.name ORDER BY st.stop_sequence))[1] AS first_stop,
                       (array_agg(s.name ORDER BY st.stop_sequence DESC))[1] AS last_stop,
                       (array_agg(s.latitude ORDER BY st.stop_sequence))[1] AS first_lat,
                       (array_agg(s.longitude ORDER BY st.stop_sequence))[1] AS first_lon,
                       (array_agg(s.latitude ORDER BY st.stop_sequence DESC))[1] AS last_lat,
                       (array_agg(s.longitude ORDER BY st.stop_sequence DESC))[1] AS last_lon
                FROM trip t JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE t.route_id = ? AND t.service_id = ?
                GROUP BY t.id
                ORDER BY t.id""",
                (rs, i) -> new TripRow(rs.getLong("id"), rs.getString("duty"), rs.getInt("direction"),
                        rs.getString("shape_id"), rs.getString("headsign"), rs.getInt("start_s"), rs.getInt("end_s"),
                        rs.getObject("km") == null ? null : rs.getDouble("km"), rs.getInt("stops"),
                        stopName(rs.getString("first_stop")), stopName(rs.getString("last_stop")),
                        new StopRef(stopName(rs.getString("first_stop")), (Double) rs.getObject("first_lat"),
                                (Double) rs.getObject("first_lon")),
                        new StopRef(stopName(rs.getString("last_stop")), (Double) rs.getObject("last_lat"),
                                (Double) rs.getObject("last_lon"))),
                route.getId(), service);

        // Variants: one per direction and shape. The base route of a direction is its most frequent shape.
        Map<String, List<TripRow>> byVariant = trips.stream().collect(Collectors.groupingBy(
                t -> variantKey(t.direction(), t.shapeId()), LinkedHashMap::new, Collectors.toList()));
        Map<Integer, String> mainShape = new HashMap<>();
        trips.stream().collect(Collectors.groupingBy(TripRow::direction)).forEach((dir, list) ->
                mainShape.put(dir, TimetableMath.mostFrequent(list.stream().map(TripRow::shapeId).toList())));

        // Stops of each variant from its trip with the most stops.
        Map<String, TripRow> representative = new LinkedHashMap<>();
        byVariant.forEach((key, list) -> representative.put(key, list.stream()
                .max(Comparator.comparingInt(TripRow::stops).thenComparing(TripRow::id, Comparator.reverseOrder()))
                .orElseThrow()));
        Map<Long, List<StopPoint>> stopsByTrip = stops(representative.values().stream().map(TripRow::id).toList());
        Map<String, List<double[]>> shapes = withShapes
                ? shapes(byVariant.values().stream().map(l -> l.get(0).shapeId()).filter(Objects::nonNull).toList())
                : Map.of();

        // Termini: where buses turn round in service, plus the base routes' ends. Depot runs start or end elsewhere.
        List<Place> endStops = endStops(trips, trips.stream()
                .filter(t -> Objects.equals(t.shapeId(), mainShape.get(t.direction()))).toList());
        Map<Long, String> depot = depotRuns(trips, endStops);

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
            long depotTrips = list.stream().filter(t -> depot.containsKey(t.id())).count();
            String kind = Objects.equals(first.shapeId(), mainShape.get(first.direction())) ? "main"
                    : depotTrips * 2 >= list.size() ? "depot"
                    : km > mainKm.getOrDefault(first.direction(), 0.0) + LONGER_MARGIN_KM ? "longer" : "shorter";
            List<Double> speeds = list.stream().filter(t -> t.km() != null && t.duration() > 0)
                    .map(t -> t.km() / (t.duration() / 3600.0)).toList();
            List<StopPoint> stops = stopsByTrip.getOrDefault(representative.get(key).id(), List.of());
            List<double[]> points = !withShapes ? List.of()
                    : shapes.containsKey(first.shapeId()) ? shapes.get(first.shapeId())
                    : stops.stream().filter(s -> s.lat() != null && s.lon() != null)
                            .map(s -> new double[]{s.lat(), s.lon()}).toList();
            variants.add(new Variant(first.direction(), first.shapeId(),
                    TimetableMath.mostFrequent(list.stream().map(TripRow::headsign).toList()), kind,
                    TimetableMath.round(km, 2), list.size(),
                    list.stream().mapToInt(t -> t.duration() / 60).min().orElse(0),
                    list.stream().mapToInt(t -> t.duration() / 60).max().orElse(0),
                    speeds.stream().min(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                    speeds.stream().max(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                    stops.isEmpty() ? null : stops.get(0).name(),
                    stops.isEmpty() ? null : stops.get(stops.size() - 1).name(), points, stops));
        });
        List<String> kindOrder = List.of("main", "longer", "shorter", "depot");
        variants.sort(Comparator.comparingInt(Variant::direction)
                .thenComparingInt(v -> kindOrder.indexOf(v.kind()))
                .thenComparing(Variant::km, Comparator.reverseOrder()));
        return new Timetable(service, trips, variants, endStops, depot);
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
                            TimetableMath.round(rs.getDouble("shape_dist_traveled"), 3), rs.getString("zone_id")));
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
                            TimetableMath.round(rs.getDouble("latitude"), 5),
                            TimetableMath.round(rs.getDouble("longitude"), 5)});
                },
                distinct.toArray());
        return result;
    }

    // --- pure helpers (unit tested) ------------------------------------------------------------------------------

    /** One bus on one brigade (or none: "") on one service day. */
    record VehicleDay(LocalDate day, String brigade, String number, String model, Instant from, Instant to,
                      int reports) {
    }

    /** A bus seen for fewer reports than this (about one a minute) on a brigade and day is left out as a glitch. */
    static final int MIN_REPORTS = 5;

    /**
     * Which buses served the line: per bus, the days and brigades; per brigade, its most used bus; and the brigades
     * that changed bus during a day, in order.
     */
    static LineDetailDto.Vehicles vehicleUse(List<VehicleDay> rows) {
        List<VehicleDay> kept = rows.stream().filter(r -> r.reports() >= MIN_REPORTS).toList();

        Map<String, List<VehicleDay>> byVehicle = kept.stream()
                .collect(Collectors.groupingBy(VehicleDay::number, TreeMap::new, Collectors.toList()));
        List<LineDetailDto.VehicleUse> vehicles = byVehicle.entrySet().stream()
                .map(e -> new LineDetailDto.VehicleUse(e.getKey(),
                        e.getValue().stream().map(VehicleDay::model).filter(Objects::nonNull).findFirst().orElse(null),
                        (int) e.getValue().stream().map(VehicleDay::day).distinct().count(),
                        e.getValue().stream().map(VehicleDay::brigade).filter(b -> !b.isEmpty()).distinct().sorted()
                                .toList(),
                        e.getValue().stream().map(VehicleDay::to).max(Comparator.naturalOrder()).orElseThrow()))
                .sorted(Comparator.comparingInt(LineDetailDto.VehicleUse::days).reversed()
                        .thenComparing(LineDetailDto.VehicleUse::number))
                .toList();

        Map<String, List<VehicleDay>> byBrigade = kept.stream().filter(r -> !r.brigade().isEmpty())
                .collect(Collectors.groupingBy(VehicleDay::brigade, TreeMap::new, Collectors.toList()));
        List<LineDetailDto.BrigadeVehicles> brigades = byBrigade.entrySet().stream().map(e -> {
            Map<String, Long> days = e.getValue().stream().collect(Collectors.groupingBy(VehicleDay::number,
                    TreeMap::new, Collectors.mapping(VehicleDay::day, Collectors.collectingAndThen(
                            Collectors.toSet(), set -> (long) set.size()))));
            var most = days.entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue()
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .orElseThrow();
            return new LineDetailDto.BrigadeVehicles(e.getKey(), most.getKey(), most.getValue().intValue(),
                    (int) e.getValue().stream().map(VehicleDay::day).distinct().count(), days.size());
        }).toList();

        List<LineDetailDto.VehicleSwitch> switches = kept.stream().filter(r -> !r.brigade().isEmpty())
                .collect(Collectors.groupingBy(r -> r.day() + "|" + r.brigade(), TreeMap::new, Collectors.toList()))
                .values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> new LineDetailDto.VehicleSwitch(list.get(0).day(), list.get(0).brigade(),
                        list.stream().sorted(Comparator.comparing(VehicleDay::from))
                                .map(r -> new LineDetailDto.VehicleSpell(r.number(), r.from(), r.to())).toList()))
                .sorted(Comparator.comparing(LineDetailDto.VehicleSwitch::day, Comparator.reverseOrder())
                        .thenComparing(LineDetailDto.VehicleSwitch::brigade))
                .toList();

        int days = (int) rows.stream().map(VehicleDay::day).distinct().count();
        return new LineDetailDto.Vehicles(days, vehicles, brigades, switches);
    }

    /**
     * The variant a recorded run covered from end to end, with its pass times, or null. The run must have the
     * variant's headsign, cover all its stops, and take between half the shortest and twice the longest scheduled
     * time. When several variants fit (they share a headsign), one whose last stop the run reached wins, then one
     * whose stop name matches the last reported next stop.
     */
    static FullRun matchRun(Track track, List<Variant> variants) {
        List<FullRun> fits = new ArrayList<>();
        for (Variant v : variants) {
            if (!Objects.equals(track.headsign(), v.headsign())) {
                continue;
            }
            double[] times = passTimes(track, kms(v));
            if (times == null) {
                continue;
            }
            double seconds = times[times.length - 1] - times[0];
            if (seconds >= v.shortestMin() * 60 * 0.5 && seconds <= v.longestMin() * 60 * 2.0) {
                fits.add(new FullRun(v.direction(), times, v));
            }
        }
        if (fits.size() <= 1) {
            return fits.isEmpty() ? null : fits.get(0);
        }
        // Prefer a variant the run reached the last stop of (not just the last but one), then one whose stop at the
        // last report's index has the reported name.
        Sample last = track.samples().get(track.samples().size() - 1);
        int highest = track.samples().stream().mapToInt(Sample::seq).max().orElse(0);
        return fits.stream().min(Comparator
                .comparing((FullRun r) -> highest != r.variant().stops().size() - 1)
                .thenComparing(r -> {
                    List<StopPoint> stops = r.variant().stops();
                    return !(last.seq() < stops.size() && last.nextStop() != null
                            && stopName(last.nextStop()).equalsIgnoreCase(stops.get(last.seq()).name()));
                })).orElseThrow();
    }

    /** Patterns with fewer scheduled trips a day than this (both directions) are too rare to chart. */
    static final int MIN_PATTERN_TRIPS = 3;

    /**
     * Route patterns to measure: each base route and longer variant together with the variants running between the
     * same two ends (in either direction). Base-route patterns come first, then by trips a day. A short turn or a
     * depot run only joins a pattern; it never starts one.
     */
    static List<List<Variant>> patterns(List<Variant> variants) {
        List<List<Variant>> patterns = new ArrayList<>();
        List<Variant> seeds = variants.stream().filter(v -> v.kind().equals("main") || v.kind().equals("longer"))
                .toList();
        for (Variant v : seeds) {
            patterns.stream().filter(p -> sameEnds(p.get(0), v)).findFirst()
                    .ifPresentOrElse(p -> p.add(v), () -> patterns.add(new ArrayList<>(List.of(v))));
        }
        for (Variant v : variants) {
            if (v.kind().equals("shorter")) {
                patterns.stream().filter(p -> sameEnds(p.get(0), v)).findFirst().ifPresent(p -> p.add(v));
            }
        }
        return patterns.stream()
                .filter(p -> p.stream().mapToInt(Variant::tripsPerDay).sum() >= MIN_PATTERN_TRIPS)
                .sorted(Comparator.comparing((List<Variant> p) -> p.stream().noneMatch(v -> v.kind().equals("main")))
                        .thenComparing(p -> -p.stream().mapToInt(Variant::tripsPerDay).sum()))
                .toList();
    }

    /** Whether two variants run between the same two ends, in either direction. */
    static boolean sameEnds(Variant a, Variant b) {
        StopRef a0 = end(a, true), a1 = end(a, false), b0 = end(b, true), b1 = end(b, false);
        return (samePlace(a0, b0) && samePlace(a1, b1)) || (samePlace(a0, b1) && samePlace(a1, b0));
    }

    /** A variant's first or last stop, with its position when the variant has its stops. */
    private static StopRef end(Variant v, boolean first) {
        if (v.stops().isEmpty()) {
            return new StopRef(first ? v.firstStop() : v.lastStop(), null, null);
        }
        StopPoint s = v.stops().get(first ? 0 : v.stops().size() - 1);
        return new StopRef(s.name(), s.lat(), s.lon());
    }

    /** Stops closer than this are one place: an end stop is often a pair of platforms or two sides of a loop. */
    static final double SAME_PLACE_METERS = 500;

    /** A stop by name, with its position when known. */
    record StopRef(String name, Double lat, Double lon) {
        boolean located() {
            return lat != null && lon != null;
        }
    }

    /** A group of nearby stops, named after the one where runs end most often. */
    record Place(String name, List<StopRef> stops) {
    }

    /** Whether two stops are one place: closer than {@link #SAME_PLACE_METERS}, or, without positions, same name. */
    static boolean samePlace(StopRef a, StopRef b) {
        if (a.located() && b.located()) {
            return meters(a.lat(), a.lon(), b.lat(), b.lon()) < SAME_PLACE_METERS;
        }
        return sameStop(a.name(), b.name());
    }

    /** Great-circle distance in metres. */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    /**
     * Whether a stop is one of the places' stops. Only the stops themselves count, not others nearby: a stop 400 m
     * from a terminus where no run turns round is still somewhere else.
     */
    static boolean atPlace(List<Place> places, StopRef stop) {
        return stop.name() != null
                && places.stream().anyMatch(p -> p.stops().stream().anyMatch(s -> s.name().equals(stop.name())));
    }

    /**
     * Groups stops into places: stops all closer than {@link #SAME_PLACE_METERS} to each other are one place. A place is named after its stop where runs end most often (an arrival), else where they start most
     * often. Places are sorted by name.
     *
     * @param arrivals   stops where runs end, once per run
     * @param departures stops where runs start, once per run
     */
    static List<Place> places(List<StopRef> arrivals, List<StopRef> departures) {
        Map<String, StopRef> distinct = new LinkedHashMap<>();
        Map<String, Integer> arriving = new HashMap<>();
        Map<String, Integer> departing = new HashMap<>();
        java.util.function.Function<StopRef, String> key = s -> s.name() + "@" + s.lat() + "," + s.lon();
        arrivals.stream().filter(s -> s.name() != null).forEach(s -> {
            distinct.putIfAbsent(key.apply(s), s);
            arriving.merge(key.apply(s), 1, Integer::sum);
        });
        departures.stream().filter(s -> s.name() != null).forEach(s -> {
            distinct.putIfAbsent(key.apply(s), s);
            departing.merge(key.apply(s), 1, Integer::sum);
        });

        // A stop joins a group only if it is close to every stop already in it, so places can't creep along a street.
        List<List<StopRef>> groups = new ArrayList<>();
        for (StopRef stop : distinct.values()) {
            groups.stream().filter(g -> g.stream().allMatch(s -> samePlace(s, stop))).findFirst()
                    .ifPresentOrElse(g -> g.add(stop), () -> groups.add(new ArrayList<>(List.of(stop))));
        }

        return groups.stream().map(group -> {
            Map<String, int[]> byName = new LinkedHashMap<>();
            for (StopRef s : group) {
                int[] counts = byName.computeIfAbsent(s.name(), n -> new int[2]);
                counts[0] += arriving.getOrDefault(key.apply(s), 0);
                counts[1] += departing.getOrDefault(key.apply(s), 0);
            }
            String name = byName.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0])
                            .thenComparingInt(e -> e.getValue()[1])
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .orElseThrow().getKey();
            return new Place(name, group);
        }).sorted(Comparator.comparing(Place::name)).toList();
    }

    private static List<LineDetailDto.EndStop> toDto(List<Place> places) {
        return places.stream().map(p -> new LineDetailDto.EndStop(p.name(), p.stops().stream().map(StopRef::name)
                .filter(n -> !n.equals(p.name())).distinct().sorted().toList())).toList();
    }

    /**
     * Whether two stop names are the same place: equal once platform numbers ("st. 4") are dropped, or one is the
     * other with a street in front ("Ciołkowskiego/Chorten Arena" and "Chorten Arena").
     */
    static boolean sameStop(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = PLATFORM.matcher(a.toLowerCase(java.util.Locale.ROOT)).replaceAll("").strip();
        String y = PLATFORM.matcher(b.toLowerCase(java.util.Locale.ROOT)).replaceAll("").strip();
        return x.equals(y) || x.endsWith("/" + y) || y.endsWith("/" + x);
    }

    private static final Pattern PLATFORM = Pattern.compile("\\s+st\\.\\s*\\d+$");

    /** Travel time and speed by departure hour for the full runs of one route pattern, with its scheduled range. */
    static LineDetailDto.TravelTimes travelTimes(String kind, String label, List<Variant> variants,
                                                 List<TripRow> trips, List<FullRun> runs) {
        Map<Integer, List<Double>> minutes = new TreeMap<>();
        Map<Integer, List<Double>> speeds = new TreeMap<>();
        for (FullRun run : runs) {
            int hour = serviceHour(run.departure());
            minutes.computeIfAbsent(hour, h -> new ArrayList<>()).add(run.minutes());
            speeds.computeIfAbsent(hour, h -> new ArrayList<>()).add(run.variant().km() / (run.minutes() / 60));
        }
        List<Double> scheduledSpeeds = trips.stream().filter(t -> t.km() != null && t.duration() > 0)
                .map(t -> t.km() / (t.duration() / 3600.0)).toList();
        return new LineDetailDto.TravelTimes(kind, label,
                TimetableMath.round(variants.stream().mapToDouble(Variant::km).average().orElse(0), 1), runs.size(),
                trips.stream().map(t -> t.duration() / 60).min(Integer::compare).orElse(null),
                trips.stream().map(t -> t.duration() / 60).max(Integer::compare).orElse(null),
                scheduledSpeeds.stream().min(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                scheduledSpeeds.stream().max(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                minutes.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 0)).toList(),
                speeds.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 1)).toList());
    }

    /** The stop name without the stop code the feed appends, e.g. "Lodowa/Chłodna(654)" → "Lodowa/Chłodna". */
    static String stopName(String name) {
        return name == null ? null : STOP_CODE.matcher(name).replaceFirst("");
    }

    /**
     * The part of a route in fare zone 2: its stops there, and its distance there. The zone border isn't in the feed,
     * so a segment between a zone-1 and a zone-2 stop counts half. Null when the route has no stop in zone 2.
     */
    static Zone2 zone2(int direction, String headsign, String kind, List<StopPoint> stops) {
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
        return new Zone2(direction, headsign, kind, count, stops.size(), TimetableMath.round(km, 1),
                TimetableMath.round(total, 1));
    }

    private static boolean inZone2(StopPoint s) {
        return "2".equals(s.zone());
    }

    /**
     * The day's brigades: each bus's runs in order, from leaving the depot (first departure) to returning to it (last
     * arrival), with the gaps between runs of at least {@link #BREAK_SECONDS} as breaks.
     *
     * @param kinds variant kind by {@link #variantKey}
     * @param depot "pull_out" or "pull_in" by trip id, for depot runs
     */
    static List<Brigade> brigades(List<TripRow> trips, Map<String, String> kinds, Map<Long, String> depot) {
        List<Brigade> brigades = new ArrayList<>();
        byDuty(trips).forEach((duty, list) -> {
            List<LineDetailDto.Run> runs = list.stream().map(t -> new LineDetailDto.Run(t.start(), t.end(),
                    t.direction(), t.headsign(), kinds.getOrDefault(variantKey(t.direction(), t.shapeId()), "main"),
                    depot.get(t.id()))).toList();
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

    /** Each brigade's runs in time order. */
    private static Map<String, List<TripRow>> byDuty(List<TripRow> trips) {
        Map<String, List<TripRow>> byDuty = new TreeMap<>();
        trips.stream().filter(t -> t.duty() != null)
                .forEach(t -> byDuty.computeIfAbsent(t.duty(), k -> new ArrayList<>()).add(t));
        byDuty.values().forEach(l -> l.sort(Comparator.comparingInt(TripRow::start).thenComparingInt(TripRow::end)));
        return byDuty;
    }

    /**
     * Standard end stops: where buses turn round in service. A run's last stop is one when the same brigade starts
     * its next run within {@link #BREAK_SECONDS}, and so is that next run's first stop; the base routes' ends always
     * are. Stops closer than {@link #SAME_PLACE_METERS} are one end stop, named after the stop where runs end. A bus
     * waiting longer at an end stop is on a break there, not at the depot.
     *
     * @param mainTrips runs of the base routes, whose ends always count
     */
    static List<Place> endStops(List<TripRow> trips, List<TripRow> mainTrips) {
        List<StopRef> arrivals = new ArrayList<>();
        List<StopRef> departures = new ArrayList<>();
        for (TripRow t : mainTrips) {
            arrivals.add(t.last());
            departures.add(t.first());
        }
        for (List<TripRow> runs : byDuty(trips).values()) {
            for (int i = 1; i < runs.size(); i++) {
                if (runs.get(i).start() - runs.get(i - 1).end() < BREAK_SECONDS) {
                    arrivals.add(runs.get(i - 1).last());
                    departures.add(runs.get(i).first());
                }
            }
        }
        return places(arrivals, departures);
    }

    /**
     * Depot runs by trip id: "pull_out" for a run that starts away from the end stops at the start of the brigade's day
     * or after a break of {@link #BREAK_SECONDS} or more; "pull_in" for one that ends away from them at the end of the
     * day or before such a break. A short turn to another stop in the middle of service is neither.
     */
    static Map<Long, String> depotRuns(List<TripRow> trips, List<Place> endStops) {
        Map<Long, String> depot = new HashMap<>();
        for (List<TripRow> runs : byDuty(trips).values()) {
            for (int i = 0; i < runs.size(); i++) {
                TripRow t = runs.get(i);
                boolean afterBreak = i == 0 || t.start() - runs.get(i - 1).end() >= BREAK_SECONDS;
                boolean beforeBreak = i == runs.size() - 1 || runs.get(i + 1).start() - t.end() >= BREAK_SECONDS;
                if (afterBreak && !atPlace(endStops, t.first())) {
                    depot.put(t.id(), "pull_out");
                } else if (beforeBreak && !atPlace(endStops, t.last())) {
                    depot.put(t.id(), "pull_in");
                }
            }
        }
        return depot;
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
        return new HourStat(hour, n, TimetableMath.round(sorted.get(0), decimals),
                TimetableMath.round(sorted.get(n - 1), decimals), TimetableMath.round(mean, decimals),
                TimetableMath.round(median, decimals));
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
                    : TimetableMath.round(km * runs.size() / (seconds / 3600), 1);
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
        return Periods.PEAK.stream().anyMatch(w -> second >= w.from() && second < w.to());
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
