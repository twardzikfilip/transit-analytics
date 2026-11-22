package com.transitanalytics.service;

import com.transitanalytics.buslanes.BusLaneMatcher;
import com.transitanalytics.buslanes.BusLanes;
import com.transitanalytics.classification.OperatorProperties;
import com.transitanalytics.domain.Route;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.BusLaneDto;
import com.transitanalytics.dto.LineDetailDto.Variant;
import com.transitanalytics.dto.LineDetailDto.Zone2;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.gtfs.GtfsProperties;
import com.transitanalytics.recording.DelayOutliers;
import com.transitanalytics.repository.RouteRepository;
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
import java.util.LinkedHashMap;
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
    private final MapProperties map;
    private final OperatorProperties operators;
    private final DepotRouter router;
    private final BusLanes busLanes;
    private final GtfsProperties feeds;
    private final RecentCache<String, List<String>> feedLines;

    public LineDetailService(JdbcTemplate jdbc, RouteRepository routes, LineTimetable timetables,
                             LineSubtypes subtypes, Clock clock, MapProperties map, OperatorProperties operators,
                             DepotRouter router, BusLanes busLanes, GtfsProperties feeds) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.timetables = timetables;
        this.subtypes = subtypes;
        this.map = map;
        this.operators = operators;
        this.router = router;
        this.busLanes = busLanes;
        this.feeds = feeds;
        this.feedLines = new RecentCache<>(clock, FEED_LINES_MAX_AGE, 1);
    }

    /**
     * All lines, numbers first in numeric order: the timetable's, and those only seen on the live feed (other
     * operators' regional lines).
     */
    @Transactional(readOnly = true)
    public List<LineDetailDto.LineRef> lines() {
        List<LineDetailDto.LineRef> lines = new ArrayList<>(routes.findAll().stream()
                .map(r -> new LineDetailDto.LineRef(r.getId(), r.getGtfsId(), r.displayName(), r.getSubtype(),
                        r.getGeometry(), r.getCapacityClass(), r.getOperatingAgency()))
                .toList());
        Set<String> known = new HashSet<>();
        lines.forEach(l -> {
            known.add(l.line());
            known.add(l.name());
        });
        feedLines().stream().filter(name -> !known.contains(name))
                .forEach(name -> lines.add(new LineDetailDto.LineRef(null, name, name, subtypes.of(name), null, null, null)));
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
            return new LineDetailDto.Detail(null, line, line, subtypes.of(line), null, null, null, null, null, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), null, depots(), List.of(), List.of(),
                    recorded(line), false);
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
                route.getGeometry(), route.getCapacityClass(), tt.service(), route.getUrl(), base, tt.variants(), zone2,
                tt.brigades() ? brigades(tt.trips(), kinds, tt.depot()) : List.of(),
                toDto(tt.endStops()), toDto(depotPlaces), depotRuns, operator(route),
                otherFeed(route) ? List.of() : depots(),
                depotLinks(route, tt.trips()), busLanes(mains), null, tt.brigades());
    }

    /** Where a line's buses went, from every recorded position of it (lines without a timetable). */
    LineDetailDto.Recorded recorded(String line) {
        return RecordedRoutes.recorded(jdbc.query("""
                SELECT vehicle_id, observed_at, latitude, longitude, headsign, next_stop_name
                FROM position_sample WHERE route_name = ?""",
                (rs, i) -> new RecordedRoutes.Report(rs.getLong("vehicle_id"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getDouble("latitude"),
                        rs.getDouble("longitude"), rs.getString("headsign"), rs.getString("next_stop_name")),
                line));
    }

    /** How much of each direction's main route runs on bus lanes. */
    private List<BusLaneDto.LineShare> busLanes(Map<Integer, Variant> mains) {
        BusLaneMatcher matcher = busLanes.current().matcher();
        return mains.values().stream().sorted(Comparator.comparingInt(Variant::direction))
                .filter(v -> v.points() != null && v.points().size() >= 2)
                .map(v -> {
                    BusLaneMatcher.Share share = matcher.share(v.points());
                    return new BusLaneDto.LineShare(v.direction(), v.firstStop(), v.lastStop(),
                            TimetableMath.round(share.meters() / 1000, 2),
                            TimetableMath.round(share.laneMeters() / 1000, 2),
                            share.meters() == 0 ? 0 : TimetableMath.round(share.laneMeters() / share.meters() * 100, 1),
                            share.onLanes());
                }).toList();
    }

    private List<LineDetailDto.Depot> depots() {
        return map.depots().stream().map(d -> new LineDetailDto.Depot(d.code(), d.name(), d.address(),
                d.outlines().stream().map(ring -> ring.stream().map(p -> new double[] {p.get(0), p.get(1)}).toList())
                        .toList())).toList();
    }

    /** Who runs a route: the organiser and its operating company with the company's depot; null when not known. */
    private LineDetailDto.Operator operator(Route route) {
        if (otherFeed(route)) {
            // Another operator's own timetable: it runs the line itself, from its own depots (not known here).
            var agency = route.getAgency();
            return agency == null ? null
                    : new LineDetailDto.Operator(agency.getName(), null, agency.getName(), agency.getUrl(), null, null);
        }
        OperatorProperties.Company company = route.getOperatingAgency() == null ? null
                : operators.company(route.getOperatingAgency());
        if (company == null) {
            return null;
        }
        MapProperties.Depot depot = map.depot(company.depot());
        return new LineDetailDto.Operator(operators.organiser(), company.code(), company.name(), company.website(),
                depot == null ? null : depot.name(), depot == null ? null : depot.address());
    }

    /**
     * Where the line's buses enter service and leave it (see {@link #entries}), each with the way between there and
     * the operator's depot (else the nearest depot): along the streets when a router answers, else straight.
     */
    private List<LineDetailDto.DepotLink> depotLinks(Route route, List<TripRow> trips) {
        if (map.depots().isEmpty() || otherFeed(route)) {
            return List.of();
        }
        OperatorProperties.Company company = route.getOperatingAgency() == null ? null
                : operators.company(route.getOperatingAgency());
        MapProperties.Depot own = company == null ? null : map.depot(company.depot());
        return entries(trips).stream().map(e -> {
            double[] stop = {e.stop().lat(), e.stop().lon()};
            MapProperties.Depot depot = own != null ? own : nearest(stop, map.depots());
            boolean out = e.kind().equals("pull_out");
            DepotRouter.Way way = out ? router.way(depot.centre(), stop) : router.way(stop, depot.centre());
            return new LineDetailDto.DepotLink(e.kind(), e.stop().name(), stop[0], stop[1], depot.code(),
                    e.brigades(), way.points(), way.routed());
        }).toList();
    }

    /**
     * A line from another operator's feed (e.g. PKS Nova's line 500): the BKM depots and companies have nothing to do
     * with it.
     */
    static boolean otherFeed(Route route) {
        return !TimetableFeed.MAIN.equals(route.getFeed());
    }

    /**
     * The operator a line's buses have on the live feed (BKM for the city's lines), so its recorded runs can be told
     * from another operator's line of the same number; null when its buses aren't on the live feed.
     */
    private String liveAgency(Route route) {
        GtfsProperties.Feed feed = feeds.feed(route.getFeed());
        return feed == null ? null : feed.agency();
    }

    /** A place where brigades enter service (pull_out) or leave it (pull_in), and how many. */
    record Entry(String kind, StopRef stop, int brigades) {
    }

    /**
     * Where the line's buses enter service (the first stop of each brigade's first trip of the day) and leave it (the
     * last stop of its last trip), with how many brigades: pull-outs first, then the busiest places.
     */
    static List<Entry> entries(List<TripRow> trips) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        LineTimetable.byDuty(trips).values().forEach(day -> {
            add(entries, "pull_out", day.get(0).first());
            add(entries, "pull_in", day.get(day.size() - 1).last());
        });
        return entries.values().stream()
                .sorted(Comparator.comparing(Entry::kind).reversed()
                        .thenComparing(Comparator.comparingInt(Entry::brigades).reversed())
                        .thenComparing(e -> e.stop().name()))
                .toList();
    }

    private static void add(Map<String, Entry> entries, String kind, StopRef stop) {
        if (stop != null && stop.located()) {
            entries.merge(kind + "|" + stop.name(), new Entry(kind, stop, 1),
                    (a, b) -> new Entry(a.kind(), a.stop(), a.brigades() + 1));
        }
    }

    /** The depot nearest a point, [lat, lon]. */
    static MapProperties.Depot nearest(double[] point, List<MapProperties.Depot> depots) {
        return depots.stream().min(Comparator.comparingDouble(d -> Geo.meters(point, d.centre()))).orElseThrow();
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
        String weekday = ServiceDay.weekdayServiceDaySql("p.observed_at");
        String ofLine = "p.route_name = ? AND p.vehicle_id IN (SELECT id FROM vehicle WHERE agency = ?) AND " + weekday;

        List<Sample> samples = jdbc.query("""
                SELECT p.vehicle_id, extract(epoch FROM p.observed_at) AS t, p.brigade, p.headsign,
                       p.next_stop_sequence AS seq, p.next_stop_name
                FROM position_sample p
                WHERE p.next_stop_sequence IS NOT NULL AND %s
                ORDER BY p.vehicle_id, p.observed_at""".formatted(ofLine),
                (rs, i) -> new Sample(rs.getLong("vehicle_id"), rs.getDouble("t"), rs.getString("brigade"),
                        rs.getString("headsign"), rs.getInt("seq"), rs.getString("next_stop_name")),
                route.getGtfsId(), liveAgency(route));

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
        List<LineDetailDto.DelaySlot> delays = delaySlots("""
                p.delay_seconds <= %d AND coalesce(p.next_stop_sequence, 1) > 0 AND %s"""
                .formatted(DelayOutliers.THRESHOLD_SECONDS, ofLine),
                route.getGtfsId(), liveAgency(route));

        TreeSet<LocalDate> days = new TreeSet<>();
        samples.forEach(s -> days.add(serviceDay(s.t())));

        List<LineDetailDto.SegmentProfile> segments = mains.values().stream()
                .map(main -> segmentProfile(main, runs.stream()
                        .filter(r -> r.variant() == main && inPeak(r.departure())).toList()))
                .toList();

        return new LineDetailDto.Performance(days.isEmpty() ? null : days.first(),
                days.isEmpty() ? null : days.last(), days.size(), runs.size(), travelTimes, delays, segments,
                vehicles(route.getGtfsId(), liveAgency(route)));
    }

    /** The average delay in each quarter of an hour of the service day, of the reports matching {@code where}. */
    private List<LineDetailDto.DelaySlot> delaySlots(String where, Object... args) {
        return jdbc.query("""
                SELECT (extract(epoch FROM %s::time)::int / 900) * 15 + %d * 60 AS minute,
                       avg(p.delay_seconds) AS delay, count(*) AS samples
                FROM position_sample p
                WHERE p.delay_seconds IS NOT NULL AND %s
                GROUP BY 1 ORDER BY 1""".formatted(ServiceDay.serviceTimeSql("p.observed_at"), DAY_START_HOUR, where),
                (rs, i) -> new LineDetailDto.DelaySlot(rs.getInt("minute"),
                        TimetableMath.round(rs.getDouble("delay"), 0), rs.getLong("samples")),
                args);
    }

    /**
     * A line not in the timetable (another operator's): with no schedule to match runs against, only the average
     * delay through the day (all days) and the buses that served it.
     */
    private LineDetailDto.Performance feedOnlyPerformance(String line) {
        List<LocalDate> days = jdbc.queryForList("""
                SELECT DISTINCT %s::date AS day
                FROM position_sample p WHERE route_name = ? ORDER BY day""".formatted(
                        ServiceDay.serviceTimeSql("p.observed_at")),
                LocalDate.class, line);
        List<LineDetailDto.DelaySlot> delays = delaySlots("abs(p.delay_seconds) <= %d AND p.route_name = ?"
                .formatted(DelayOutliers.THRESHOLD_SECONDS), line);
        return new LineDetailDto.Performance(days.isEmpty() ? null : days.get(0),
                days.isEmpty() ? null : days.get(days.size() - 1), days.size(), 0, List.of(), delays, List.of(),
                vehicles(line, null));
    }

    /** Which buses (of the operator, or any when null) served the line, from every recorded day of it. */
    private LineDetailDto.Vehicles vehicles(String line, String agency) {
        List<VehicleDay> rows = jdbc.query("""
                SELECT %s::date AS day,
                       coalesce(p.brigade, '') AS brigade, v.vehicle_number, v.model,
                       min(p.observed_at) AS first_seen, max(p.observed_at) AS last_seen, count(*) AS reports
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE p.route_name = ? AND (CAST(? AS text) IS NULL OR v.agency = ?)
                GROUP BY 1, 2, 3, 4""".formatted(ServiceDay.serviceTimeSql("p.observed_at")),
                (rs, i) -> new VehicleDay(rs.getObject("day", LocalDate.class), rs.getString("brigade"),
                        rs.getString("vehicle_number"), rs.getString("model"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getTimestamp("last_seen").toInstant(),
                        rs.getInt("reports")),
                line, agency, agency);
        return vehicleUse(rows);
    }

    // --- timetable ---------------------------------------------------------------------------------------------

    /**
     * @param line the route's GTFS id, e.g. "103" or "500"; the city's when another feed has the same id; null when the
     *             timetable has no such route
     */
    private Route findRoute(String line) {
        return jdbc.queryForList("SELECT id FROM route WHERE gtfs_id = ? ORDER BY feed <> ?, feed LIMIT 1", Long.class,
                        line, TimetableFeed.MAIN).stream().findFirst()
                .flatMap(routes::findById).orElse(null);
    }
}
