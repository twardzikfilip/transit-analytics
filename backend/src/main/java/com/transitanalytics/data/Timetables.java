package com.transitanalytics.data;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.gtfs.GtfsProperties;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.timetable.StopPlaces;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The bus timetables' trips of a service day, with their stops. */
@Component
public class Timetables {

    /** A stop of a trip: its name and place. */
    public record Stop(String name, double lat, double lon) {
    }

    private final JdbcTemplate jdbc;
    /** See {@link #recordedFeeds(GtfsProperties)}. */
    private final String[] recordedFeeds;

    public Timetables(JdbcTemplate jdbc, GtfsProperties feeds) {
        this.jdbc = jdbc;
        this.recordedFeeds = recordedFeeds(feeds);
    }

    /**
     * The feeds whose buses are on the live feed. Other feeds' trips (PKS Nova's route 500) are left out: nothing can be
     * recorded for them, so they would all look cancelled.
     */
    static String[] recordedFeeds(GtfsProperties feeds) {
        return Stream.concat(Stream.of(TimetableFeed.MAIN), feeds.extra().keySet().stream())
                .filter(key -> feeds.feed(key).agency() != null).toArray(String[]::new);
    }

    /** The trips of the recorded feeds' services running that day, of one brigade only (null: all). */
    public List<ScheduledTrip> trips(LocalDate day, String brigade) {
        return trips(day, recordedFeeds, brigade);
    }

    /**
     * The trips of some feeds running that day, of one brigade only (null: all). Their shapes are keyed as
     * {@link Shapes#keySql}.
     */
    public List<ScheduledTrip> trips(LocalDate day, String[] feeds, String brigade) {
        record Row(long trip, String tripId, String route, String brigade, String headsign, String shapeId, int seq,
                   String stop, int departure, int arrival, double lat, double lon) {
        }
        List<Row> rows = jdbc.query("""
                SELECT t.id, t.gtfs_id, r.gtfs_id AS line, %s AS brigade, t.headsign, %s AS shape_id, st.stop_sequence,
                       s.name, s.latitude, s.longitude, extract(epoch FROM st.departure_time::interval)::int AS departure,
                       extract(epoch FROM st.arrival_time::interval)::int AS arrival
                FROM trip t JOIN route r ON r.id = t.route_id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE t.feed = ANY (?) AND %s%s
                ORDER BY t.id, st.stop_sequence""".formatted(ServiceDay.BRIGADE_SQL, Shapes.keySql("t"),
                        ServiceDay.TRIPS_OF_DAY_SQL,
                        brigade == null ? "" : " AND " + ServiceDay.BRIGADE_SQL + " = ?"),
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("gtfs_id"), rs.getString("line"),
                        rs.getString("brigade"), rs.getString("headsign"), rs.getString("shape_id"),
                        rs.getInt("stop_sequence"), StopPlaces.stopName(rs.getString("name")),
                        rs.getInt("departure"), rs.getInt("arrival"), rs.getDouble("latitude"),
                        rs.getDouble("longitude")),
                Stream.concat(Stream.<Object>of((Object) feeds), Arrays.stream(brigade == null
                        ? ServiceDay.tripsOfDayArgs(day) : new Object[] {day, day.plusDays(1), brigade})).toArray());
        Map<Long, List<Row>> byTrip = new LinkedHashMap<>();
        rows.forEach(r -> byTrip.computeIfAbsent(r.trip(), k -> new ArrayList<>()).add(r));
        List<ScheduledTrip> trips = new ArrayList<>();
        for (List<Row> stops : byTrip.values()) {
            Row first = stops.get(0);
            Row last = stops.get(stops.size() - 1);
            Instant zero = ServiceDay.tripTimeZero(day, first.departure());
            trips.add(new ScheduledTrip(first.tripId(), first.route(), first.brigade(), first.headsign(),
                    zero.plusSeconds(first.departure()), zero.plusSeconds(last.arrival()),
                    stops.stream().mapToInt(Row::seq).toArray(), stops.stream().map(Row::stop).toArray(String[]::new),
                    stops.stream().mapToInt(row -> row.departure() - first.departure()).toArray(), first.shapeId(),
                    new double[] {last.lat(), last.lon()}));
        }
        return trips;
    }

    /**
     * Each route's stops along the way (of the recorded feeds' trips), by route; a stop where any of the route's trips
     * starts or ends is left out (buses lay over there).
     */
    public Map<String, List<Stop>> stopsAlongRoutes() {
        Map<String, List<Stop>> byRoute = new HashMap<>();
        jdbc.query("""
                WITH ends AS (
                    SELECT st.trip_id, min(st.stop_sequence) AS first, max(st.stop_sequence) AS last
                    FROM stop_time st JOIN trip t ON t.id = st.trip_id WHERE t.feed = ANY (?) GROUP BY st.trip_id)
                SELECT r.gtfs_id AS route, s.name, s.latitude, s.longitude
                FROM trip t JOIN route r ON r.id = t.route_id JOIN ends e ON e.trip_id = t.id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE t.feed = ANY (?) AND s.latitude IS NOT NULL
                GROUP BY r.gtfs_id, s.id, s.name, s.latitude, s.longitude
                HAVING NOT bool_or(st.stop_sequence IN (e.first, e.last))""",
                rs -> {
                    byRoute.computeIfAbsent(rs.getString("route"), k -> new ArrayList<>()).add(new Stop(
                            StopPlaces.stopName(rs.getString("name")), rs.getDouble("latitude"),
                            rs.getDouble("longitude")));
                }, recordedFeeds, recordedFeeds);
        return byRoute;
    }

    /** The stops of a route's trip (its first trip with that id), in order. */
    public List<Stop> stops(String route, String tripId) {
        return jdbc.query("""
                SELECT s.name, s.latitude, s.longitude FROM stop_time st JOIN stop s ON s.id = st.stop_id
                WHERE st.trip_id = (SELECT min(t2.id) FROM trip t2 JOIN route r ON r.id = t2.route_id
                                    WHERE t2.gtfs_id = ? AND r.gtfs_id = ?)
                ORDER BY st.stop_sequence""",
                (rs, i) -> new Stop(StopPlaces.stopName(rs.getString(1)), rs.getDouble(2), rs.getDouble(3)),
                tripId, route);
    }
}
