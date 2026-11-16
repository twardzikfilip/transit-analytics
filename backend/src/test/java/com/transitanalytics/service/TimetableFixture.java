package com.transitanalytics.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a small timetable for integration tests: a service running on every weekday of 2099 (so it is the weekday
 * timetable the pages use), routes, stops, trips with their stop times, and route shapes. Use in a
 * {@code @Transactional} test so it is rolled back.
 */
final class TimetableFixture {

    private final JdbcTemplate jdbc;
    private final String serviceId;

    /** Creates the weekday service {@code serviceId}. */
    TimetableFixture(JdbcTemplate jdbc, String serviceId) {
        this.jdbc = jdbc;
        this.serviceId = serviceId;
        jdbc.update("""
                INSERT INTO calendar_date (service_id, date, exception_type)
                SELECT ?, d::date, 1 FROM generate_series(date '2099-01-01', date '2099-12-31', interval '1 day') d
                WHERE extract(isodow FROM d) <= 5""", serviceId);
    }

    long route(String gtfsId, String name) {
        return jdbc.queryForObject("INSERT INTO route (gtfs_id, long_name, route_type) VALUES (?, ?, 3) RETURNING id",
                Long.class, gtfsId, name);
    }

    long route(String gtfsId, String name, String subtype) {
        return jdbc.queryForObject("""
                INSERT INTO route (gtfs_id, long_name, route_type, subtype) VALUES (?, ?, 3, ?) RETURNING id""",
                Long.class, gtfsId, name, subtype);
    }

    /** A stop at the latitude given, longitude 23.1; {@code zone} may be null. */
    void stop(String gtfsId, String name, String zone, double latitude) {
        jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude, zone_id) VALUES (?, ?, ?, ?, 23.1, ?)",
                gtfsId, gtfsId, name, latitude, zone);
    }

    /**
     * A trip leaving its first stop at {@code start} ("HH:MM"), one minute between stops, arriving at the last one
     * {@code minutes} after the start.
     *
     * @param stops "stop gtfs id:km along the route", or just the stop gtfs id for one km between stops
     */
    long trip(long route, String gtfsId, int direction, String headsign, String shape, String start, int minutes,
              String... stops) {
        long trip = jdbc.queryForObject("""
                INSERT INTO trip (gtfs_id, route_id, service_id, direction, headsign, shape_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, gtfsId, route, serviceId, direction, headsign,
                shape);
        int startMinute = Integer.parseInt(start.substring(0, 2)) * 60 + Integer.parseInt(start.substring(3));
        for (int i = 0; i < stops.length; i++) {
            String[] parts = stops[i].split(":");
            int m = startMinute + (i == stops.length - 1 ? minutes : i);
            String time = "%02d:%02d:00".formatted(m / 60, m % 60);
            jdbc.update("""
                    INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time,
                                           shape_dist_traveled)
                    VALUES (?, (SELECT id FROM stop WHERE gtfs_id = ?), ?, ?, ?, ?)""",
                    trip, parts[0], i + 1, time, time, parts.length > 1 ? Double.parseDouble(parts[1]) : (double) i);
        }
        return trip;
    }

    /** A two-point route shape. */
    void shape(String shapeId) {
        jdbc.update("INSERT INTO shape_point (shape_id, sequence, latitude, longitude) VALUES (?, 1, 53.1, 23.1), "
                + "(?, 2, 53.2, 23.2)", shapeId, shapeId);
    }
}
