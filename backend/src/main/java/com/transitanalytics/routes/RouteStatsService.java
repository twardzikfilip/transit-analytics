package com.transitanalytics.routes;

import com.transitanalytics.domain.Route;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.RouteStatsDto;
import com.transitanalytics.timetable.Periods.Window;
import com.transitanalytics.timetable.Periods;
import com.transitanalytics.timetable.Routes;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.timetable.WeekdayTimetable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.transitanalytics.timetable.Periods.OFF_PEAK;
import static com.transitanalytics.timetable.Periods.PEAK;
import static com.transitanalytics.timetable.TimetableMath.ROUTE_ORDER;
import static com.transitanalytics.timetable.TimetableMath.interval;
import static com.transitanalytics.timetable.TimetableMath.mostFrequent;
import static com.transitanalytics.timetable.TimetableMath.regularity;
import static com.transitanalytics.timetable.TimetableMath.round;

/**
 * Statistics per route from the regular weekday timetable: length, shortest and longest travel time, and average
 * speed, interval and vehicles in peak and off-peak hours.
 */
@Service
public class RouteStatsService {

    /** Recorded delays are averaged over the weekdays of this many last days. */
    static final int DELAY_DAYS = 28;

    /**
     * One timetable trip, reduced to what the statistics need.
     *
     * @param duty  the vehicle duty (one bus), e.g. "103-01"
     * @param start first departure, seconds after midnight (may exceed 24 h)
     * @param end   last arrival, seconds after midnight
     * @param km    distance along the route shape, null when the feed has none
     */
    record TripTimes(long routeId, int direction, String shapeId, String duty, int start, int end, Double km) {
    }

    private final JdbcTemplate jdbc;
    private final Routes routes;
    private final WeekdayTimetable weekdayTimetable;

    public RouteStatsService(JdbcTemplate jdbc, Routes routes, WeekdayTimetable weekdayTimetable) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.weekdayTimetable = weekdayTimetable;
    }

    public RouteStatsDto.Report weekdayReport() {
        String service = weekdayTimetable.serviceId();
        if (service == null) {
            // No timetable imported yet.
            return new RouteStatsDto.Report(null, Periods.toDto(PEAK), Periods.toDto(OFF_PEAK), DELAY_DAYS, List.of());
        }

        // One row per trip: start/end in seconds (GTFS times can pass 24:00), distance along the shape and the vehicle
        // duty (brigade).
        List<TripTimes> trips = jdbc.query("""
                SELECT t.route_id, coalesce(t.direction, 0) AS direction, t.shape_id,
                       %s AS duty,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       max(st.shape_dist_traveled) AS km
                FROM trip t JOIN stop_time st ON st.trip_id = t.id
                WHERE t.feed = ? AND t.service_id = ?
                GROUP BY t.id""".formatted(ServiceDay.BRIGADE_SQL),
                (rs, i) -> new TripTimes(rs.getLong("route_id"), rs.getInt("direction"), rs.getString("shape_id"),
                        rs.getString("duty"), rs.getInt("start_s"), rs.getInt("end_s"),
                        rs.getObject("km") == null ? null : rs.getDouble("km")),
                TimetableFeed.MAIN, service);

        // Departures at each direction's reference stop (first stop of its main variant), for regularity. Every trip
        // of that direction serving the stop counts, once (a loop route returns to its first stop).
        Map<Long, Map<Integer, List<Integer>>> departures = new HashMap<>();
        jdbc.query("""
                WITH t AS (
                    SELECT id, route_id, coalesce(direction, 0) AS dir, shape_id FROM trip WHERE feed = ? AND service_id = ?),
                main AS (
                    SELECT DISTINCT ON (route_id, dir) route_id, dir, shape_id
                    FROM t GROUP BY route_id, dir, shape_id
                    ORDER BY route_id, dir, count(*) DESC, shape_id NULLS LAST),
                ref AS (
                    SELECT DISTINCT ON (m.route_id, m.dir) m.route_id, m.dir, st.stop_id
                    FROM main m
                    JOIN t ON t.route_id = m.route_id AND t.dir = m.dir AND t.shape_id IS NOT DISTINCT FROM m.shape_id
                    JOIN stop_time st ON st.trip_id = t.id
                    ORDER BY m.route_id, m.dir, st.stop_sequence)
                SELECT DISTINCT ON (t.id) r.route_id, r.dir,
                       extract(epoch FROM st.departure_time::interval)::int AS departure_s
                FROM ref r
                JOIN t ON t.route_id = r.route_id AND t.dir = r.dir
                JOIN stop_time st ON st.trip_id = t.id AND st.stop_id = r.stop_id
                WHERE st.departure_time IS NOT NULL
                ORDER BY t.id, st.stop_sequence""",
                rs -> {
                    departures.computeIfAbsent(rs.getLong("route_id"), k -> new HashMap<>())
                            .computeIfAbsent(rs.getInt("dir"), k -> new ArrayList<>())
                            .add(rs.getInt("departure_s"));
                },
                TimetableFeed.MAIN, service);

        Map<Long, Route> routeById = routes.all().stream().collect(Collectors.toMap(Route::id, Function.identity()));
        List<RouteStatsDto.Route> stats = trips.stream()
                .collect(Collectors.groupingBy(TripTimes::routeId))
                .entrySet().stream()
                .map(e -> routeStats(routeById.get(e.getKey()), e.getKey(), e.getValue(),
                        departures.getOrDefault(e.getKey(), Map.of())))
                .sorted(Comparator.comparing(RouteStatsDto.Route::route, ROUTE_ORDER))
                .toList();
        Map<String, Double[]> delays = recordedDelays();
        stats = stats.stream().map(l -> {
            Double[] d = delays.getOrDefault(l.route(), new Double[2]);
            return l.withDelays(d[0], d[1]);
        }).toList();

        return new RouteStatsDto.Report(service, Periods.toDto(PEAK), Periods.toDto(OFF_PEAK), DELAY_DAYS, stats);
    }

    /**
     * Each route's mean recorded delay in peak and off-peak hours (seconds, [peak, off-peak]) on weekdays of the last
     * {@link #DELAY_DAYS} days, from the hourly delay aggregate. An hour counts in the part of the day its middle falls
     * in (07:00–08:00 is peak when peak starts at 06:30): the aggregate has whole hours only.
     */
    Map<String, Double[]> recordedDelays() {
        Map<String, Double[]> delays = new HashMap<>();
        // The hours are worked out from constants, so they can go into the SQL as they are.
        jdbc.query("""
                WITH local AS (
                    SELECT route_name, delay_stats,
                           extract(hour FROM bucket AT TIME ZONE '%1$s')::int AS hour
                    FROM route_delay_hourly
                    WHERE bucket >= now() - make_interval(days => ?)
                      AND %4$s)
                SELECT route_name,
                       average(rollup(delay_stats) FILTER (WHERE hour IN (%2$s))) AS peak,
                       average(rollup(delay_stats) FILTER (WHERE hour IN (%3$s))) AS off_peak
                FROM local GROUP BY route_name""".formatted(ServiceDay.ZONE.getId(), hours(PEAK), hours(OFF_PEAK),
                        ServiceDay.weekdaySql("(bucket AT TIME ZONE '%s')".formatted(ServiceDay.ZONE.getId()))),
                rs -> {
                    delays.put(rs.getString("route_name"),
                            new Double[] {rounded(rs.getObject("peak")), rounded(rs.getObject("off_peak"))});
                },
                DELAY_DAYS);
        return delays;
    }

    /** The hours of the day whose middle falls in the windows, e.g. "6, 7, 15, 16"; "-1" (no hour) for none. */
    static String hours(List<Window> windows) {
        List<String> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (Periods.in(windows, h * 3600 + 1800)) hours.add(String.valueOf(h));
        }
        return hours.isEmpty() ? "-1" : String.join(", ", hours);
    }

    private static Double rounded(Object value) {
        return value == null ? null : (double) Math.round(((Number) value).doubleValue());
    }

    static RouteStatsDto.Route routeStats(Route route, long routeId, List<TripTimes> trips) {
        return routeStats(route, routeId, trips, Map.of());
    }

    /** @param departures departure times (seconds) at the reference stop, per direction, for regularity */
    static RouteStatsDto.Route routeStats(Route route, long routeId, List<TripTimes> trips,
                                       Map<Integer, List<Integer>> departures) {
        Map<Integer, List<TripTimes>> byDirection = trips.stream().collect(Collectors.groupingBy(TripTimes::direction));
        // Trip start times per direction: the interval counts every trip starting in a period.
        Map<Integer, List<Integer>> starts = new HashMap<>();
        byDirection.forEach((dir, list) -> starts.put(dir, list.stream().map(TripTimes::start).toList()));

        // Main variant of each direction: its most frequent shape. Length and travel times use only those trips, so
        // short turns and depot runs don't count as "the route".
        List<TripTimes> mainTrips = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        for (List<TripTimes> direction : byDirection.values()) {
            String mainShape = mostFrequent(direction.stream().map(TripTimes::shapeId).toList());
            List<TripTimes> main = direction.stream().filter(t -> Objects.equals(t.shapeId(), mainShape)).toList();
            mainTrips.addAll(main);
            main.stream().map(TripTimes::km).filter(Objects::nonNull).max(Double::compare).ifPresent(lengths::add);
        }
        Double lengthKm = lengths.isEmpty() ? null : round(lengths.stream().mapToDouble(d -> d).average().orElse(0), 1);

        return new RouteStatsDto.Route(routeId, route == null ? String.valueOf(routeId) : route.displayName(),
                route == null ? null : route.color(), route == null ? null : route.textColor(), lengthKm,
                speed(mainTrips, PEAK), speed(mainTrips, OFF_PEAK),
                interval(starts, PEAK), interval(starts, OFF_PEAK),
                regularity(departures, PEAK), regularity(departures, OFF_PEAK),
                duties(trips, PEAK), trips.size(), null, null);
    }

    /**
     * Average speed in km/h of the trips starting in the periods: their total distance over their total travel time,
     * so longer trips weigh more. Null when no such trip has a distance and a duration.
     */
    static Double speed(List<TripTimes> trips, List<Window> windows) {
        double km = 0;
        double seconds = 0;
        for (TripTimes t : trips) {
            if (t.km() != null && t.end() > t.start() && Periods.in(windows, t.start())) {
                km += t.km();
                seconds += t.end() - t.start();
            }
        }
        return seconds == 0 ? null : round(km / (seconds / 3600), 1);
    }

    /** Duties (buses) needed: those with a trip under way in a window, for the busiest of the windows. */
    static int duties(List<TripTimes> trips, List<Window> windows) {
        return windows.stream().mapToInt(window -> {
            Set<String> duties = new HashSet<>();
            trips.stream().filter(t -> window.overlaps(t.start(), t.end())).map(TripTimes::duty)
                    .filter(Objects::nonNull).forEach(duties::add);
            return duties.size();
        }).max().orElse(0);
    }

}
