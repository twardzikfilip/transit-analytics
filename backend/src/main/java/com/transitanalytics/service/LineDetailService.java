package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.LineDetailDto.Variant;
import com.transitanalytics.dto.LineDetailDto.Zone2;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.recording.DelayOutliers;
import com.transitanalytics.repository.RouteRepository;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.transitanalytics.service.LineTimetable.*;
import static com.transitanalytics.service.Periods.*;
import static com.transitanalytics.service.RunTracker.*;
import static com.transitanalytics.service.ServiceDay.*;
import static com.transitanalytics.service.StopPlaces.*;
import static com.transitanalytics.service.TimetableMath.*;
import static com.transitanalytics.service.VehicleUsage.*;

/**
 * One line in detail. From the regular weekday timetable: its variants, lengths, scheduled travel times and speeds,
 * the part outside the city (zone 2) and the brigades (bus duties). From the recorded vehicle positions (weekdays):
 * travel time and speed of full runs by hour, average delay over the day and speed between stops in peak hours.
 */
@Service
public class LineDetailService {

    /** Lines seen on the live feed are read again after this long. */
    static final Duration FEED_LINES_MAX_AGE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final RouteRepository routes;
    private final LineTimetable timetables;
    private final LineSubtypes subtypes;
    private final RecentCache<String, List<String>> feedLines;

    public LineDetailService(JdbcTemplate jdbc, RouteRepository routes, LineTimetable timetables,
                             LineSubtypes subtypes, Clock clock) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.timetables = timetables;
        this.subtypes = subtypes;
        this.feedLines = new RecentCache<>(clock, FEED_LINES_MAX_AGE, 1);
    }

    /**
     * All lines, numbers first in numeric order: the timetable's, and those only seen on the live feed (other
     * operators' regional lines).
     */
    @Transactional(readOnly = true)
    public List<LineDetailDto.LineRef> lines() {
        List<LineDetailDto.LineRef> lines = new ArrayList<>(routes.findAll().stream()
                .map(r -> new LineDetailDto.LineRef(r.getId(), r.getGtfsId(), r.displayName(), r.getSubtype()))
                .toList());
        Set<String> known = new HashSet<>();
        lines.forEach(l -> {
            known.add(l.line());
            known.add(l.name());
        });
        feedLines().stream().filter(name -> !known.contains(name))
                .forEach(name -> lines.add(new LineDetailDto.LineRef(null, name, name, subtypes.of(name))));
        lines.sort(Comparator.comparing(LineDetailDto.LineRef::name, TimetableMath.LINE_ORDER));
        return lines;
    }

    private void requireOnFeed(String line) {
        if (!feedLines().contains(line)) {
            throw new NotFoundException("Line", line);
        }
    }

    /** Every line name recorded on the live feed. */
    private List<String> feedLines() {
        // A loose index scan over (route_name, observed_at): one index lookup per name, not a scan of every report.
        return feedLines.get("all", () -> jdbc.queryForList("""
                WITH RECURSIVE names AS (
                    (SELECT route_name FROM position_sample WHERE route_name > '' ORDER BY route_name LIMIT 1)
                    UNION ALL
                    SELECT (SELECT p.route_name FROM position_sample p WHERE p.route_name > n.route_name
                            ORDER BY p.route_name LIMIT 1)
                    FROM names n WHERE n.route_name IS NOT NULL)
                SELECT route_name FROM names WHERE route_name IS NOT NULL""", String.class));
    }

    @Transactional(readOnly = true)
    public LineDetailDto.Detail detail(String line) {
        Route route = findRoute(line);
        if (route == null) {
            requireOnFeed(line);
            // Only on the live feed: no timetable to show.
            return new LineDetailDto.Detail(null, line, line, subtypes.of(line), null, null, null, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Timetable tt = timetables.timetable(route, true);
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
                tt.service(), route.getUrl(), base, tt.variants(), zone2, brigades(tt.trips(), kinds, tt.depot()),
                toDto(tt.endStops()), toDto(depotPlaces), depotRuns);
    }

    @Transactional(readOnly = true)
    public LineDetailDto.Performance performance(String line) {
        Route route = findRoute(line);
        if (route == null) {
            requireOnFeed(line);
            return feedOnlyPerformance(line);
        }
        Timetable tt = timetables.timetable(route, false);
        Map<Integer, Variant> mains = tt.mains();

        // Weekday service days: a report at 00:30 on Saturday still belongs to Friday's service.
        String weekday = "extract(isodow FROM (p.observed_at AT TIME ZONE '%s') - make_interval(hours => %d)) <= 5"
                .formatted(ZONE.getId(), DAY_START_HOUR);
        String ofLine = "p.route_name = ? AND p.vehicle_id IN (SELECT id FROM vehicle WHERE agency = ?) AND " + weekday;

        List<Sample> samples = jdbc.query("""
                SELECT p.vehicle_id, extract(epoch FROM p.observed_at) AS t, p.brigade, p.headsign,
                       p.next_stop_sequence AS seq, p.next_stop_name
                FROM position_sample p
                WHERE p.next_stop_sequence IS NOT NULL AND %s
                ORDER BY p.vehicle_id, p.observed_at""".formatted(ofLine),
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
                  AND coalesce(p.next_stop_sequence, 1) > 0 AND %4$s
                GROUP BY 1 ORDER BY 1""".formatted(ZONE.getId(), DAY_START_HOUR, DelayOutliers.THRESHOLD_SECONDS,
                        ofLine),
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
                vehicles(route.getGtfsId(), ZbiorkomBialystokService.CITY_AGENCY));
    }

    /**
     * A line not in the timetable (another operator's): with no schedule to match runs against, only the average
     * delay through the day (all days) and the buses that served it.
     */
    private LineDetailDto.Performance feedOnlyPerformance(String line) {
        List<LocalDate> days = jdbc.queryForList("""
                SELECT DISTINCT ((observed_at AT TIME ZONE '%s') - make_interval(hours => %d))::date AS day
                FROM position_sample WHERE route_name = ? ORDER BY day""".formatted(ZONE.getId(), DAY_START_HOUR),
                LocalDate.class, line);
        List<LineDetailDto.DelaySlot> delays = jdbc.query("""
                SELECT (extract(epoch FROM ((p.observed_at AT TIME ZONE '%1$s') - make_interval(hours => %2$d))::time)
                        ::int / 900) * 15 + %2$d * 60 AS minute,
                       avg(p.delay_seconds) AS delay, count(*) AS samples
                FROM position_sample p
                WHERE p.delay_seconds IS NOT NULL AND abs(p.delay_seconds) <= %3$d AND p.route_name = ?
                GROUP BY 1 ORDER BY 1""".formatted(ZONE.getId(), DAY_START_HOUR, DelayOutliers.THRESHOLD_SECONDS),
                (rs, i) -> new LineDetailDto.DelaySlot(rs.getInt("minute"),
                        TimetableMath.round(rs.getDouble("delay"), 0), rs.getLong("samples")),
                line);
        return new LineDetailDto.Performance(days.isEmpty() ? null : days.get(0),
                days.isEmpty() ? null : days.get(days.size() - 1), days.size(), 0, List.of(), delays, List.of(),
                vehicles(line, null));
    }

    /** Which buses (of the operator, or any when null) served the line, from every recorded day of it. */
    private LineDetailDto.Vehicles vehicles(String line, String agency) {
        List<VehicleDay> rows = jdbc.query("""
                SELECT ((p.observed_at AT TIME ZONE '%s') - make_interval(hours => %d))::date AS day,
                       coalesce(p.brigade, '') AS brigade, v.vehicle_number, v.model,
                       min(p.observed_at) AS first_seen, max(p.observed_at) AS last_seen, count(*) AS reports
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE p.route_name = ? AND (CAST(? AS text) IS NULL OR v.agency = ?)
                GROUP BY 1, 2, 3, 4""".formatted(ZONE.getId(), DAY_START_HOUR),
                (rs, i) -> new VehicleDay(rs.getObject("day", LocalDate.class), rs.getString("brigade"),
                        rs.getString("vehicle_number"), rs.getString("model"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant(),
                        rs.getInt("reports")),
                line, agency, agency);
        return vehicleUse(rows);
    }

    // --- timetable ---------------------------------------------------------------------------------------------

    /** @param line the route's GTFS id, e.g. "103"; null when the timetable has no such route */
    private Route findRoute(String line) {
        return jdbc.queryForList("SELECT id FROM route WHERE gtfs_id = ?", Long.class, line).stream().findFirst()
                .flatMap(routes::findById).orElse(null);
    }
}
