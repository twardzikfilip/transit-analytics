package com.transitanalytics.timetable;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.TimetableDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The timetable's stops and stop times as the Stops and Stop times pages list them (trains left out). */
@Component
public class StopTimetable {

    private final JdbcTemplate jdbc;

    public StopTimetable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stops by name, those with the text in their name or code, in the zone when one is given. */
    public TimetableDto.Page<TimetableDto.Stop> stops(String q, String zoneId, int page, int size) {
        List<Object> args = new ArrayList<>(List.of(TimetableFeed.RAIL));
        String where = "feed <> ?";
        if (q != null && !q.isBlank()) {
            where += " AND (lower(name) LIKE ? ESCAPE '\\' OR lower(code) LIKE ? ESCAPE '\\')";
            args.add(like(q));
            args.add(like(q));
        }
        if (zoneId != null && !zoneId.isBlank()) {
            where += " AND zone_id = ?";
            args.add(zoneId);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM stop WHERE " + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<TimetableDto.Stop> rows = jdbc.query("""
                SELECT id, feed, code, name, latitude, longitude, zone_id FROM stop
                WHERE %s ORDER BY name, id LIMIT ? OFFSET ?""".formatted(where),
                (rs, i) -> new TimetableDto.Stop(rs.getLong("id"), rs.getString("feed"), rs.getString("code"),
                        rs.getString("name"), (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"),
                        rs.getString("zone_id")), args.toArray());
        return TimetableDto.Page.of(rows, page, size, total);
    }

    /** A trip's calls in order, a stop's by departure, or all. */
    public TimetableDto.Page<TimetableDto.StopTime> stopTimes(Long tripId, Long stopId, int page, int size) {
        List<Object> args = new ArrayList<>(List.of(TimetableFeed.RAIL));
        String where = "t.feed <> ?";
        if (tripId != null) {
            where += " AND st.trip_id = ?";
            args.add(tripId);
        }
        if (stopId != null) {
            where += " AND st.stop_id = ?";
            args.add(stopId);
        }
        String order = tripId != null ? "st.stop_sequence" : stopId != null ? "st.departure_time, st.id" : "st.id";
        long total = jdbc.queryForObject("SELECT count(*) FROM stop_time st JOIN trip t ON t.id = st.trip_id WHERE "
                + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<TimetableDto.StopTime> rows = jdbc.query("""
                SELECT st.id, st.trip_id, t.gtfs_id AS trip_gtfs_id, t.headsign, t.service_id, coalesce(nullif(r.short_name, ''), r.long_name) AS route,
                       st.stop_id, s.name AS stop, st.stop_sequence, st.arrival_time, st.departure_time,
                       st.stop_headsign, st.pickup_type, st.drop_off_type
                FROM stop_time st JOIN trip t ON t.id = st.trip_id JOIN route r ON r.id = t.route_id
                     JOIN stop s ON s.id = st.stop_id
                WHERE %s ORDER BY %s LIMIT ? OFFSET ?""".formatted(where, order),
                (rs, i) -> new TimetableDto.StopTime(rs.getLong("id"), rs.getLong("trip_id"),
                        rs.getString("trip_gtfs_id"), rs.getString("headsign"),
                        rs.getString("service_id"), rs.getString("route"), rs.getLong("stop_id"),
                        rs.getString("stop"), rs.getInt("stop_sequence"), rs.getString("arrival_time"),
                        rs.getString("departure_time"), rs.getString("stop_headsign"),
                        small(rs.getObject("pickup_type")), small(rs.getObject("drop_off_type"))),
                args.toArray());
        return TimetableDto.Page.of(rows, page, size, total);
    }

    private static Short small(Object value) {
        return value == null ? null : ((Number) value).shortValue();
    }

    private static String like(String text) {
        return "%" + text.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
