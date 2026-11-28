package com.transitanalytics.data;

import com.transitanalytics.domain.TimetableFeed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The trains of the rail timetable (Polregio's and PKP Intercity's), with their calls and tracks. */
@Component
public class RailTrains {

    /**
     * A train's call at a station; a platform counts as its station.
     *
     * @param station   the station's id in the timetable
     * @param place     the station's row id ({@code stop.id})
     * @param arrival   seconds after the timetable day's midnight (its departure when it has no arrival)
     * @param departure …and its departure (its arrival when it has none)
     * @param boards    whether passengers may get on, and {@code alights} off
     */
    public record Call(String station, long place, String name, double lat, double lon, int arrival, int departure,
                       boolean boards, boolean alights) {

        public double[] at() {
            return new double[] {lat, lon};
        }
    }

    /**
     * @param agency  its operator's id ("PR" for Polregio)
     * @param service its timetable service
     * @param name    its number and name as the timetable has them, else its route's name
     * @param label   its number and name without the train's category ("16132/3 Dąbrowska (P44)": "16132/3 Dąbrowska")
     * @param shape   its route shape's id, null when it has none
     * @param track   its route shape, [latitude, longitude]; empty when the timetable has none
     */
    public record Train(long id, String agency, String service, String name, String label, String shape,
                        List<double[]> track, List<Call> calls) {
    }

    /** A station, by its id in the timetable. */
    public record Station(String id, String name, double lat, double lon) {

        public double[] at() {
            return new double[] {lat, lon};
        }
    }

    private final JdbcTemplate jdbc;
    private final Shapes shapes;

    public RailTrains(JdbcTemplate jdbc, Shapes shapes) {
        this.jdbc = jdbc;
        this.shapes = shapes;
    }

    /** The trains running on a date. */
    public List<Train> of(LocalDate day) {
        return read("""
                AND EXISTS (SELECT 1 FROM calendar_date c WHERE c.feed = t.feed AND c.service_id = t.service_id
                                AND c.exception_type = 1 AND c.date = ?)""", TimetableFeed.RAIL, day);
    }

    /** Every train of the timetable, whatever day it runs. */
    public List<Train> all() {
        return read("", TimetableFeed.RAIL);
    }

    /** The dates from {@code from} on that each timetable service runs. */
    public Map<String, Set<LocalDate>> serviceDates(LocalDate from) {
        Map<String, Set<LocalDate>> dates = new HashMap<>();
        jdbc.query("SELECT service_id, date FROM calendar_date WHERE feed = ? AND exception_type = 1 AND date >= ?",
                rs -> {
                    dates.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getObject(2, LocalDate.class));
                }, TimetableFeed.RAIL, from);
        return dates;
    }

    /** Some stations, by id, in id order. */
    public List<Station> stations(Collection<String> ids) {
        return jdbc.query("""
                SELECT gtfs_id, name, latitude, longitude FROM stop
                WHERE feed = ? AND gtfs_id = ANY (?) ORDER BY gtfs_id""",
                (rs, i) -> new Station(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)),
                TimetableFeed.RAIL, ids.toArray(String[]::new));
    }

    private List<Train> read(String condition, Object... args) {
        record Row(long id, String agency, String service, String name, String label, String shape,
                   List<Call> calls) {
        }
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, a.gtfs_id AS agency, t.service_id, t.short_name, t.shape_id,
                       coalesce(nullif(t.short_name, ''), nullif(r.short_name, ''), r.long_name) AS name,
                       coalesce(p.gtfs_id, s.gtfs_id) AS station, coalesce(p.id, s.id) AS place,
                       coalesce(p.name, s.name) AS station_name, coalesce(p.latitude, s.latitude) AS lat,
                       coalesce(p.longitude, s.longitude) AS lon,
                       extract(epoch FROM coalesce(st.arrival_time, st.departure_time)::interval)::int AS arrival,
                       extract(epoch FROM coalesce(st.departure_time, st.arrival_time)::interval)::int AS departure,
                       coalesce(st.pickup_type, 0) <> 1 AS boards, coalesce(st.drop_off_type, 0) <> 1 AS alights
                FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                     LEFT JOIN stop p ON p.feed = s.feed AND p.gtfs_id = s.parent_station
                WHERE t.feed = ? AND coalesce(st.arrival_time, st.departure_time) IS NOT NULL %s
                ORDER BY t.id, st.stop_sequence""".formatted(condition),
                rs -> {
                    long id = rs.getLong("id");
                    Row row = rows.get(id);
                    if (row == null) {
                        String number = rs.getString("short_name");
                        String label = number == null ? "" : number.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                        row = new Row(id, rs.getString("agency"), rs.getString("service_id"), rs.getString("name"),
                                label, rs.getString("shape_id"), new ArrayList<>());
                        rows.put(id, row);
                    }
                    row.calls().add(new Call(rs.getString("station"), rs.getLong("place"),
                            rs.getString("station_name"), rs.getDouble("lat"), rs.getDouble("lon"),
                            rs.getInt("arrival"), rs.getInt("departure"), rs.getBoolean("boards"),
                            rs.getBoolean("alights")));
                }, args);
        Map<String, List<double[]>> tracks = shapes.of(TimetableFeed.RAIL,
                rows.values().stream().map(Row::shape).filter(Objects::nonNull).toList());
        return rows.values().stream().map(r -> new Train(r.id(), r.agency(), r.service(), r.name(), r.label(),
                r.shape(), tracks.getOrDefault(r.shape(), List.of()), List.copyOf(r.calls()))).toList();
    }
}
