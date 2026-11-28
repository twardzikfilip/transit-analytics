package com.transitanalytics.timetable;

import com.transitanalytics.domain.TimetableFeed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Finds the regular weekday timetable (GTFS service) the timetable pages are computed from. */
@Component
public class WeekdayTimetable {

    private final JdbcTemplate jdbc;

    public WeekdayTimetable(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The city's (main feed's) service that runs on the most upcoming weekdays (else on the most weekdays overall). Null when no
     * timetable is imported.
     */
    public String serviceId() {
        List<String> services = jdbc.queryForList("""
                SELECT service_id FROM calendar_date
                WHERE feed = ? AND exception_type = 1 AND %s
                GROUP BY service_id
                ORDER BY count(*) FILTER (WHERE date >= current_date) DESC, count(*) DESC, service_id
                LIMIT 1""".formatted(ServiceDay.weekdaySql("date")), String.class, TimetableFeed.MAIN);
        return services.isEmpty() ? null : services.get(0);
    }
}
