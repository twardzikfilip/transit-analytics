package com.transitanalytics.corridors;

import com.transitanalytics.common.WarmUp;
import com.transitanalytics.data.Shapes;
import com.transitanalytics.data.Shapes.MeasuredPoint;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.timetable.Periods.Window;
import com.transitanalytics.timetable.Periods;
import com.transitanalytics.timetable.StopPlaces.StopRef;
import com.transitanalytics.timetable.StopPlaces;
import com.transitanalytics.timetable.WeekdayTimetable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.transitanalytics.corridors.CorridorBuilder.*;
import static com.transitanalytics.corridors.SegmentLoads.*;
import static com.transitanalytics.corridors.StretchStats.*;
import static com.transitanalytics.timetable.Periods.*;
import static com.transitanalytics.timetable.StopPlaces.*;
import static com.transitanalytics.timetable.TimetableMath.*;

/**
 * Important corridors: stretches of consecutive stops that two or more routes share, from the regular weekday
 * timetable. Every set of routes gets the longest stretches it shares, so corridors can overlap (routes A and B along a
 * street, and A, B and C on part of it). Each corridor has its length, buses a day, and the interval and regularity of
 * those buses in peak and off-peak hours, with both directions in one row.
 */
@Service
public class CorridorService {

    private final JdbcTemplate jdbc;
    private final Shapes shapes;
    private final WeekdayTimetable weekdayTimetable;
    private final Map<String, CorridorDto.Report> cache = new ConcurrentHashMap<>();

    public CorridorService(JdbcTemplate jdbc, Shapes shapes, WeekdayTimetable weekdayTimetable) {
        this.jdbc = jdbc;
        this.shapes = shapes;
        this.weekdayTimetable = weekdayTimetable;
    }

    /** Computes the corridors once at startup, in the background, so the first visit doesn't wait for it. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        WarmUp.inBackground("corridors", CorridorService.class, this::corridors);
    }

    @Transactional(readOnly = true)
    public CorridorDto.Report corridors() {
        String service = weekdayTimetable.serviceId();
        if (service == null) {
            return new CorridorDto.Report(null, periods(PEAK), periods(OFF_PEAK), List.of(), List.of());
        }
        // The result only changes with the timetable: keyed by the service and its trips, so an import refreshes it.
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT count(*) AS trips, coalesce(max(id), 0) AS last FROM trip WHERE feed = ? AND service_id = ?",
                TimetableFeed.MAIN, service);
        String key = service + ":" + version.get("trips") + ":" + version.get("last");
        CorridorDto.Report report = cache.get(key);
        if (report == null) {
            // One computation at a time (the startup warm-up and a first visit may overlap); only the newest is kept.
            synchronized (cache) {
                report = cache.get(key);
                if (report == null) {
                    report = compute(service);
                    cache.clear();
                    cache.put(key, report);
                }
            }
        }
        return report;
    }

    private CorridorDto.Report compute(String service) {
        Map<Long, StopRef> stops = new HashMap<>();
        Map<Long, List<Object[]>> rows = new LinkedHashMap<>();
        Map<Long, String> routeOf = new HashMap<>();
        Map<Long, String> shapeOf = new HashMap<>();
        jdbc.query("""
                SELECT t.id AS trip, t.shape_id, coalesce(nullif(r.short_name, ''), r.long_name) AS line, st.stop_id,
                       s.name,
                       s.latitude, s.longitude, extract(epoch FROM st.departure_time::interval)::int AS departure,
                       st.shape_dist_traveled AS km
                FROM trip t
                JOIN route r ON r.id = t.route_id
                JOIN stop_time st ON st.trip_id = t.id
                JOIN stop s ON s.id = st.stop_id
                WHERE t.feed = ? AND t.service_id = ?
                ORDER BY t.id, st.stop_sequence""",
                rs -> {
                    long stop = rs.getLong("stop_id");
                    if (!stops.containsKey(stop)) {
                        stops.put(stop, new StopRef(StopPlaces.stopName(rs.getString("name")),
                                (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")));
                    }
                    long trip = rs.getLong("trip");
                    routeOf.put(trip, rs.getString("line"));
                    shapeOf.put(trip, rs.getString("shape_id"));
                    rows.computeIfAbsent(trip, k -> new ArrayList<>()).add(new Object[]{stop,
                            rs.getObject("departure") == null ? -1 : rs.getInt("departure"),
                            rs.getObject("km") == null ? null : rs.getDouble("km")});
                },
                TimetableFeed.MAIN, service);
        List<TripStops> trips = new ArrayList<>();
        rows.forEach((id, list) -> {
            long[] s = new long[list.size()];
            int[] d = new int[list.size()];
            Double[] km = new Double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                s[i] = (Long) list.get(i)[0];
                d[i] = (Integer) list.get(i)[1];
                km[i] = (Double) list.get(i)[2];
            }
            trips.add(new TripStops(id, routeOf.get(id), s, d, km, shapeOf.get(id)));
        });

        Map<String, List<MeasuredPoint>> shapes = this.shapes.measured(TimetableFeed.MAIN, service);

        return new CorridorDto.Report(service, periods(PEAK), periods(OFF_PEAK), corridors(trips, stops),
                segmentLoads(trips, stops, shapes));
    }

    private static List<com.transitanalytics.dto.RouteStatsDto.Period> periods(List<Window> windows) {
        return Periods.toDto(windows);
    }

    // --- pure helpers (unit tested) ------------------------------------------------------------------------------

    /**
     * The corridors of these trips, busiest first: the longest stretches shared by each set of routes, without shorter
     * ones that don't carry clearly more buses than a longer one around them, then joined end to end where the way on
     * doesn't fork.
     */
    static List<CorridorDto.Corridor> corridors(List<TripStops> trips, Map<Long, StopRef> stops) {
        java.util.function.Function<Long, String> name = id -> stops.get(id).name();
        List<Path> paths = paths(segments(trips), MIN_STOPS, name);
        List<Path> kept = withoutQuietSubStretches(paths, wholePathBuses(paths, trips), KEEP_SUB_STRETCH_RATIO);
        List<Path> merged = joinEndToEnd(kept, name);
        Map<List<Long>, Stretch> stretches = stretches(trips);
        List<CorridorDto.Corridor> result = new ArrayList<>();
        for (Path[] pair : pairDirections(merged, stops)) {
            result.add(corridor(pair, stretches, stops));
        }
        result.sort(Comparator.comparingInt(CorridorDto.Corridor::buses).reversed()
                .thenComparing(c -> -c.stops().size())
                .thenComparing(CorridorDto.Corridor::from));
        return result;
    }
}
