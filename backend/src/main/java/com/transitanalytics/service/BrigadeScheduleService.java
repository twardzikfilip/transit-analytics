package com.transitanalytics.service;

import com.transitanalytics.dto.BrigadeDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.transitanalytics.service.StopPlaces.stopName;

/** A brigade's runs on the current service day, for the live map. */
@Service
public class BrigadeScheduleService {

    private final JdbcTemplate jdbc;

    public BrigadeScheduleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The runs of {@code brigade} on the service day that {@code now} falls in (03:00 to 03:00 local time). */
    public BrigadeDto.Schedule schedule(String brigade, Instant now) {
        LocalDate day = ServiceDay.serviceDay(now);
        Instant dayStart = ServiceDay.gtfsTimeZero(day);
        List<BrigadeDto.Run> runs = jdbc.query("""
                SELECT coalesce(nullif(r.short_name, ''), r.long_name) AS line, t.headsign,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       (array_agg(s.name ORDER BY st.stop_sequence))[1] AS first_stop,
                       (array_agg(s.name ORDER BY st.stop_sequence DESC))[1] AS last_stop
                FROM trip t
                JOIN route r ON r.id = t.route_id
                JOIN stop_time st ON st.trip_id = t.id
                JOIN stop s ON s.id = st.stop_id
                WHERE t.service_id IN (SELECT service_id FROM calendar_date WHERE date = ? AND exception_type = 1)
                  AND %s = ?
                GROUP BY t.id, r.short_name, r.long_name, t.headsign
                ORDER BY start_s, t.id""".formatted(ServiceDay.BRIGADE_SQL),
                (rs, i) -> new BrigadeDto.Run(rs.getString("line"), rs.getString("headsign"),
                        stopName(rs.getString("first_stop")), stopName(rs.getString("last_stop")),
                        dayStart.plusSeconds(rs.getInt("start_s")), dayStart.plusSeconds(rs.getInt("end_s"))),
                day, brigade);
        return new BrigadeDto.Schedule(brigade, day, runs);
    }
}
