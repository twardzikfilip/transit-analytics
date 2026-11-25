package com.transitanalytics.gtfs;

import com.transitanalytics.domain.CityTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Continues another operator's timetable after its last day, when no newer one is published: every day gets the
 * services of the same weekday in the last known week. Such days are marked in {@code assumed_timetable_day}. The
 * city's own feed covers months and is never continued.
 * <p>
 * Public holidays aren't known: they get their weekday's services.
 */
@Component
public class AssumedTimetable {

    /** Days continued ahead of today. */
    static final int AHEAD_DAYS = 7;
    /** How long after its last day a timetable is continued: later it is too old to go by. */
    static final int MAX_DAYS = 56;

    private static final Logger log = LoggerFactory.getLogger(AssumedTimetable.class);

    private final JdbcTemplate jdbc;
    private final GtfsProperties properties;
    private final Clock clock;

    public AssumedTimetable(JdbcTemplate jdbc, GtfsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Before anything is worked out from the timetable at startup. */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void atStartup() {
        properties.extra().keySet().forEach(this::extend);
    }

    /** After the nightly imports: a feed that couldn't be imported is continued by a day too. */
    @Scheduled(cron = "0 58 4 * * *", zone = CityTime.ZONE_ID)
    @Transactional
    public void nightly() {
        properties.extra().keySet().forEach(this::extend);
    }

    /** Right after an import, in its transaction: the days the new file has aren't assumed any more. */
    void imported(String feed) {
        jdbc.update("""
                DELETE FROM assumed_timetable_day a USING calendar_date c
                WHERE a.feed = ? AND c.feed = a.feed AND c.date = a.date AND c.exception_type = 1""", feed);
        if (properties.extra().containsKey(feed)) {
            extend(feed);
        }
    }

    /** Continues a feed to {@link #AHEAD_DAYS} days from today, at most {@link #MAX_DAYS} days after its last day. */
    void extend(String feed) {
        LocalDate last = jdbc.queryForObject("""
                SELECT max(c.date) FROM calendar_date c
                WHERE c.feed = ? AND c.exception_type = 1
                  AND NOT EXISTS (SELECT 1 FROM assumed_timetable_day a WHERE a.feed = c.feed AND a.date = c.date)
                  AND EXISTS (SELECT 1 FROM trip t WHERE t.feed = c.feed AND t.service_id = c.service_id)""",
                LocalDate.class, feed);
        LocalDate today = LocalDate.now(clock.withZone(CityTime.ZONE));
        LocalDate until = today.plusDays(AHEAD_DAYS);
        // Nothing is made up while the feed itself still runs on for long enough.
        if (last == null || !last.isBefore(until)) {
            return;
        }
        if (until.isAfter(last.plusDays(MAX_DAYS))) {
            until = last.plusDays(MAX_DAYS);
        }
        // The day a whole number of weeks earlier that is within the last known week.
        int added = jdbc.update("""
                INSERT INTO calendar_date (feed, service_id, date, exception_type)
                SELECT c.feed, c.service_id, d.date, 1
                FROM (SELECT ?::date + n AS date, ?::date - 6 + (n - 1) % 7 AS source
                      FROM generate_series(1, ?::date - ?::date) n) d
                JOIN calendar_date c ON c.feed = ? AND c.date = d.source AND c.exception_type = 1
                ON CONFLICT (feed, service_id, date) DO NOTHING""",
                Date.valueOf(last), Date.valueOf(last), Date.valueOf(until), Date.valueOf(last), feed);
        if (added > 0) {
            jdbc.update("""
                    INSERT INTO assumed_timetable_day (feed, date)
                    SELECT DISTINCT feed, date FROM calendar_date WHERE feed = ? AND date > ? AND exception_type = 1
                    ON CONFLICT DO NOTHING""", feed, Date.valueOf(last));
            log.warn("Timetable {} ends on {}: continued to {} with its last known week", feed, last, until);
        }
    }
}
