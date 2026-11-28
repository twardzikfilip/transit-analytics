package com.transitanalytics.data;

import com.transitanalytics.domain.TimetableFeed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** Route shapes of the timetables: each the points of a route in order, [latitude, longitude]. */
@Component
public class Shapes {

    /** A shape's point with how far along the shape it is, as the feed gives it (km in the city's timetable). */
    public record MeasuredPoint(double lat, double lon, Double km) {
    }

    private final JdbcTemplate jdbc;

    public Shapes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * SQL: a shape's key among all feeds' (feeds reuse each other's shape ids), on a table alias with {@code feed} and
     * {@code shape_id}; null when there is no shape.
     */
    public static String keySql(String alias) {
        return alias + ".feed || '/' || " + alias + ".shape_id";
    }

    /** Some shapes of a feed, by id. */
    public Map<String, List<double[]>> of(String feed, Collection<String> ids) {
        Map<String, List<double[]>> shapes = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.query("""
                    SELECT shape_id, latitude, longitude FROM shape_point
                    WHERE feed = ? AND shape_id = ANY (?) ORDER BY shape_id, sequence""",
                    rs -> {
                        shapes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new double[] {rs.getDouble(2), rs.getDouble(3)});
                    }, feed, ids.stream().distinct().toArray(String[]::new));
        }
        return shapes;
    }

    /** Every shape of a feed, by id. */
    public Map<String, List<double[]>> all(String feed) {
        Map<String, List<double[]>> shapes = new HashMap<>();
        jdbc.query("SELECT shape_id, latitude, longitude FROM shape_point WHERE feed = ? ORDER BY shape_id, sequence",
                rs -> {
                    shapes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble(2), rs.getDouble(3)});
                }, feed);
        return shapes;
    }

    /** Every bus shape (all the timetables but the trains'), by key ({@link #keySql}). */
    public Map<String, List<double[]>> buses() {
        Map<String, List<double[]>> shapes = new HashMap<>();
        jdbc.query("SELECT " + keySql("sp") + ", latitude, longitude FROM shape_point sp WHERE feed <> ?"
                        + " ORDER BY feed, shape_id, sequence",
                rs -> {
                    shapes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble(2), rs.getDouble(3)});
                }, TimetableFeed.RAIL);
        return shapes;
    }

    /** Changes whenever the bus timetables do (another import): {@link #buses()} read with an older one is out of date. */
    public String busesVersion() {
        Map<String, Object> v = jdbc.queryForMap("SELECT count(*) AS trips, max(id) AS last FROM trip WHERE feed <> ?",
                TimetableFeed.RAIL);
        return v.get("trips") + ":" + v.get("last");
    }

    /** The shape of a route's trip (its first trip with that id); empty when it has none. */
    public List<double[]> ofTrip(String route, String tripId) {
        return jdbc.query("""
                SELECT sp.latitude, sp.longitude FROM trip t JOIN shape_point sp
                    ON sp.feed = t.feed AND sp.shape_id = t.shape_id
                WHERE t.id = (SELECT min(t2.id) FROM trip t2 JOIN route r ON r.id = t2.route_id
                              WHERE t2.gtfs_id = ? AND r.gtfs_id = ?)
                ORDER BY sp.sequence""", (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2)}, tripId, route);
    }

    /** The shapes of a feed's trips on a timetable service, by id, with how far along each point is. */
    public Map<String, List<MeasuredPoint>> measured(String feed, String service) {
        Map<String, List<MeasuredPoint>> shapes = new HashMap<>();
        jdbc.query("""
                SELECT sp.shape_id, sp.latitude, sp.longitude, sp.shape_dist_traveled
                FROM shape_point sp
                WHERE sp.feed = ? AND sp.shape_id IN (SELECT DISTINCT shape_id FROM trip WHERE feed = ? AND service_id = ?)
                ORDER BY sp.shape_id, sp.sequence""",
                rs -> {
                    shapes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new MeasuredPoint(
                            rs.getDouble(2), rs.getDouble(3), (Double) rs.getObject(4)));
                }, feed, feed, service);
        return shapes;
    }

    /** Each route's main shape (its most common one, preferring the first direction), by route id. */
    public Map<Long, List<double[]>> mainOfRoutes() {
        Map<Long, List<double[]>> shapes = new HashMap<>();
        jdbc.query("""
                WITH used AS (
                    SELECT route_id, feed, coalesce(direction, 0) AS dir, shape_id, count(*) AS n
                    FROM trip WHERE shape_id IS NOT NULL GROUP BY 1, 2, 3, 4),
                main AS (
                    SELECT DISTINCT ON (route_id) route_id, feed, shape_id FROM used
                    ORDER BY route_id, (dir <> 0), n DESC, shape_id)
                SELECT m.route_id, sp.latitude, sp.longitude
                FROM main m JOIN shape_point sp ON sp.feed = m.feed AND sp.shape_id = m.shape_id
                ORDER BY m.route_id, sp.sequence""",
                rs -> {
                    shapes.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble(2), rs.getDouble(3)});
                });
        return shapes;
    }
}
