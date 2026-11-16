package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.LineStatsDto;
import com.transitanalytics.repository.RouteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Statistics per line from the regular weekday timetable: length, shortest and longest travel time, and average
 * speed, interval and vehicles in peak and off-peak hours.
 */
@Service
public class LineStatsService {

    /** Peak periods (seconds after midnight): 06:00–09:00 and 15:00–17:00. */
    static final List<Window> PEAK = List.of(new Window(6 * 3600, 9 * 3600), new Window(15 * 3600, 17 * 3600));
    /** Off-peak period: 17:00–22:00. */
    static final Window OFF_PEAK = new Window(17 * 3600, 22 * 3600);

    /** A period of the service day, in seconds after midnight; {@code to} is exclusive. */
    record Window(int from, int to) {
        int minutes() {
            return (to - from) / 60;
        }

        boolean startsIn(TripTimes t) {
            return t.start() >= from && t.start() < to;
        }

        boolean overlaps(TripTimes t) {
            return t.start() < to && t.end() > from;
        }

        LineStatsDto.Period toDto() {
            return new LineStatsDto.Period(hhmm(from), hhmm(to));
        }

        private static String hhmm(int seconds) {
            return "%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60);
        }
    }

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
    private final RouteRepository routes;

    public LineStatsService(JdbcTemplate jdbc, RouteRepository routes) {
        this.jdbc = jdbc;
        this.routes = routes;
    }

    @Transactional(readOnly = true)
    public LineStatsDto.Report weekdayReport() {
        // The weekday service that runs on the most upcoming weekdays (else on the most weekdays overall).
        List<String> services = jdbc.queryForList("""
                SELECT service_id FROM calendar_date
                WHERE exception_type = 1 AND extract(isodow FROM date) <= 5
                GROUP BY service_id
                ORDER BY count(*) FILTER (WHERE date >= current_date) DESC, count(*) DESC, service_id
                LIMIT 1""", String.class);
        if (services.isEmpty()) {
            // No timetable imported yet.
            return new LineStatsDto.Report(null, 0, null, null, PEAK.stream().map(Window::toDto).toList(),
                    OFF_PEAK.toDto(), List.of());
        }
        String service = services.get(0);
        var days = jdbc.queryForMap("""
                SELECT count(*) AS days, min(date) AS first, max(date) AS last FROM calendar_date
                WHERE service_id = ? AND exception_type = 1 AND extract(isodow FROM date) <= 5""", service);

        // One row per trip: start/end in seconds (GTFS times can pass 24:00) and distance along the shape. The vehicle
        // duty is the trip id's prefix in this feed (e.g. 103-01_P_1349_…); block_id is used where a feed provides it.
        List<TripTimes> trips = jdbc.query("""
                SELECT t.route_id, coalesce(t.direction, 0) AS direction, t.shape_id,
                       coalesce(t.block_id, split_part(t.gtfs_id, '_', 1)) AS duty,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       max(st.shape_dist_traveled) AS km
                FROM trip t JOIN stop_time st ON st.trip_id = t.id
                WHERE t.service_id = ?
                GROUP BY t.id""",
                (rs, i) -> new TripTimes(rs.getLong("route_id"), rs.getInt("direction"), rs.getString("shape_id"),
                        rs.getString("duty"), rs.getInt("start_s"), rs.getInt("end_s"),
                        rs.getObject("km") == null ? null : rs.getDouble("km")),
                service);

        // Departures at each direction's reference stop (first stop of its main variant), for regularity. Every trip
        // of that direction serving the stop counts, once (a loop line returns to its first stop).
        Map<Long, Map<Integer, List<Integer>>> departures = new HashMap<>();
        jdbc.query("""
                WITH t AS (
                    SELECT id, route_id, coalesce(direction, 0) AS dir, shape_id FROM trip WHERE service_id = ?),
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
                service);

        Map<Long, Route> routeById = routes.findAll().stream().collect(Collectors.toMap(Route::getId, Function.identity()));
        List<LineStatsDto.Line> lines = trips.stream()
                .collect(Collectors.groupingBy(TripTimes::routeId))
                .entrySet().stream()
                .map(e -> lineStats(routeById.get(e.getKey()), e.getKey(), e.getValue(),
                        departures.getOrDefault(e.getKey(), Map.of())))
                .sorted(Comparator.comparing(LineStatsDto.Line::route, LINE_ORDER))
                .toList();

        return new LineStatsDto.Report(service, ((Number) days.get("days")).intValue(),
                toLocalDate(days.get("first")), toLocalDate(days.get("last")),
                PEAK.stream().map(Window::toDto).toList(), OFF_PEAK.toDto(), lines);
    }

    static LineStatsDto.Line lineStats(Route route, long routeId, List<TripTimes> trips) {
        return lineStats(route, routeId, trips, Map.of());
    }

    /** @param departures departure times (seconds) at the reference stop, per direction, for regularity */
    static LineStatsDto.Line lineStats(Route route, long routeId, List<TripTimes> trips,
                                       Map<Integer, List<Integer>> departures) {
        Map<Integer, List<TripTimes>> byDirection = trips.stream().collect(Collectors.groupingBy(TripTimes::direction));

        // Main variant of each direction: its most frequent shape. Length and travel times use only those trips, so
        // short turns and depot runs don't count as "the line".
        List<TripTimes> mainTrips = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        for (List<TripTimes> direction : byDirection.values()) {
            String mainShape = mostFrequent(direction.stream().map(TripTimes::shapeId).toList());
            List<TripTimes> main = direction.stream().filter(t -> Objects.equals(t.shapeId(), mainShape)).toList();
            mainTrips.addAll(main);
            main.stream().map(TripTimes::km).filter(Objects::nonNull).max(Double::compare).ifPresent(lengths::add);
        }
        Double lengthKm = lengths.isEmpty() ? null : round(lengths.stream().mapToDouble(d -> d).average().orElse(0), 1);
        Integer shortest = mainTrips.stream().map(t -> t.end() - t.start()).min(Integer::compare).orElse(null);
        Integer longest = mainTrips.stream().map(t -> t.end() - t.start()).max(Integer::compare).orElse(null);

        return new LineStatsDto.Line(routeId, route == null ? String.valueOf(routeId) : route.displayName(),
                route == null ? null : route.getColor(), route == null ? null : route.getTextColor(), lengthKm,
                shortest, longest, speed(mainTrips, PEAK), speed(mainTrips, List.of(OFF_PEAK)),
                interval(byDirection.values(), PEAK), interval(byDirection.values(), List.of(OFF_PEAK)),
                regularity(departures, PEAK), regularity(departures, List.of(OFF_PEAK)),
                PEAK.stream().mapToInt(w -> duties(trips, w)).max().orElse(0), duties(trips, OFF_PEAK), trips.size());
    }

    /**
     * Average minutes between departures: the periods' total minutes divided by the trips starting in them, per
     * direction, then averaged over the directions that have trips then. Null when no trip starts in the periods.
     */
    static Double interval(Collection<List<TripTimes>> directions, List<Window> windows) {
        int minutes = windows.stream().mapToInt(Window::minutes).sum();
        List<Double> perDirection = new ArrayList<>();
        for (List<TripTimes> direction : directions) {
            long departures = direction.stream().filter(t -> windows.stream().anyMatch(w -> w.startsIn(t))).count();
            if (departures > 0) {
                perDirection.add((double) minutes / departures);
            }
        }
        return perDirection.isEmpty() ? null : round(perDirection.stream().mapToDouble(d -> d).average().orElse(0), 1);
    }

    /**
     * Average speed in km/h of the trips starting in the periods: their total distance over their total travel time,
     * so longer trips weigh more. Null when no such trip has a distance and a duration.
     */
    static Double speed(List<TripTimes> trips, List<Window> windows) {
        double km = 0;
        double seconds = 0;
        for (TripTimes t : trips) {
            if (t.km() != null && t.end() > t.start() && windows.stream().anyMatch(w -> w.startsIn(t))) {
                km += t.km();
                seconds += t.end() - t.start();
            }
        }
        return seconds == 0 ? null : round(km / (seconds / 3600), 1);
    }

    /**
     * How evenly departures are spread: the coefficient of variation of the gaps between consecutive departures
     * (population standard deviation / mean; 0 = perfectly even). Computed per direction and per period, so the
     * break between the two peak periods is never a gap, then averaged weighted by the number of gaps. A direction
     * and period needs at least 3 gaps; null when none qualifies.
     *
     * <p>A useful reading: for passengers arriving at random, the expected wait is mean gap / 2 × (1 + CV²).
     */
    static Double regularity(Map<Integer, List<Integer>> departuresByDirection, List<Window> windows) {
        double weightedCv = 0;
        int gapCount = 0;
        for (List<Integer> times : departuresByDirection.values()) {
            for (Window w : windows) {
                List<Integer> in = times.stream().filter(t -> t >= w.from() && t < w.to()).sorted().toList();
                if (in.size() < 4) {
                    continue;
                }
                double[] gaps = new double[in.size() - 1];
                for (int i = 1; i < in.size(); i++) {
                    gaps[i - 1] = in.get(i) - in.get(i - 1);
                }
                double mean = java.util.Arrays.stream(gaps).average().orElse(0);
                if (mean <= 0) {
                    continue;
                }
                double variance = java.util.Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0);
                weightedCv += Math.sqrt(variance) / mean * gaps.length;
                gapCount += gaps.length;
            }
        }
        return gapCount == 0 ? null : round(weightedCv / gapCount, 2);
    }

    /** Duties (buses) with a trip running at some point in the window. */
    static int duties(List<TripTimes> trips, Window window) {
        Set<String> duties = new HashSet<>();
        trips.stream().filter(window::overlaps).map(TripTimes::duty).filter(Objects::nonNull).forEach(duties::add);
        return duties.size();
    }

    private static String mostFrequent(List<String> values) {
        Map<String, Long> counts = new HashMap<>();
        values.forEach(v -> counts.merge(v, 1L, Long::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey,
                        Comparator.nullsFirst(Comparator.<String>reverseOrder())))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static double round(double value, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(value * f) / f;
    }

    private static LocalDate toLocalDate(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    /** Numbers in numeric order, then names with letters (N1, N2, …). */
    static final Comparator<String> LINE_ORDER = (a, b) -> {
        boolean na = a.chars().allMatch(Character::isDigit);
        boolean nb = b.chars().allMatch(Character::isDigit);
        if (na && nb) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        return na != nb ? (na ? -1 : 1) : a.compareTo(b);
    };
}
