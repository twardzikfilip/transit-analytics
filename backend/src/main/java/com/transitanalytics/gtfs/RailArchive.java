package com.transitanalytics.gtfs;

import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.TimetableFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Keeps the train timetables of every day ({@code rail_train}, {@code rail_train_call}, {@code rail_train_day}), which
 * the next imports replace. Days from today on are kept as the newest timetable has them, past days as they last were.
 */
@Component
public class RailArchive {

    private static final Logger log = LoggerFactory.getLogger(RailArchive.class);

    /** The loaded trains with their calls as one key; trains stored already have the same one. */
    private static final String KEYED = """
            CREATE TEMP TABLE rail_keyed ON COMMIT DROP AS
            SELECT t.id AS trip, t.service_id, a.gtfs_id AS agency, t.gtfs_id, t.short_name AS number, t.headsign,
                   md5(concat_ws('|', a.gtfs_id, t.gtfs_id, t.short_name, t.headsign,
                                 string_agg(concat_ws(',', st.stop_sequence, s.gtfs_id, s.name, st.arrival_time,
                                                      st.departure_time), ';' ORDER BY st.stop_sequence))) AS key
            FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                 JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
            WHERE t.feed = ?
            GROUP BY t.id, a.gtfs_id""";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RailArchive(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What was imported before the archive existed, or while it failed. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void atStartup() {
        keep();
    }

    @EventListener
    @Transactional
    public void afterImport(GtfsImported imported) {
        if (TimetableFeed.RAIL.equals(imported.feed())) {
            keep();
        }
    }

    private void keep() {
        Date today = Date.valueOf(LocalDate.now(clock.withZone(CityTime.ZONE)));
        jdbc.execute("DROP TABLE IF EXISTS rail_keyed");
        jdbc.update(KEYED, TimetableFeed.RAIL);
        int trains = jdbc.update("""
                INSERT INTO rail_train (key, agency, gtfs_id, number, headsign)
                SELECT DISTINCT ON (key) key, agency, gtfs_id, number, headsign FROM rail_keyed
                ON CONFLICT (key) DO NOTHING""");
        jdbc.update("""
                INSERT INTO rail_train_call (train_id, stop_sequence, station_id, station, stop_id, arrival_time,
                                             departure_time)
                SELECT n.id, st.stop_sequence, coalesce(s.parent_station, s.gtfs_id), s.name, s.gtfs_id,
                       st.arrival_time, st.departure_time
                FROM (SELECT DISTINCT ON (key) key, trip FROM rail_keyed) k JOIN rail_train n ON n.key = k.key
                     JOIN stop_time st ON st.trip_id = k.trip JOIN stop s ON s.id = st.stop_id
                ON CONFLICT DO NOTHING""");
        // Only days the timetable itself has: those it was continued with aren't published.
        jdbc.execute("DROP TABLE IF EXISTS rail_published");
        jdbc.update("""
                CREATE TEMP TABLE rail_published ON COMMIT DROP AS
                SELECT c.service_id, c.date FROM calendar_date c
                WHERE c.feed = ? AND c.exception_type = 1 AND NOT EXISTS (
                    SELECT 1 FROM assumed_timetable_day a WHERE a.feed = c.feed AND a.date = c.date)""",
                TimetableFeed.RAIL);
        // Past days stay; one the archive doesn't have yet is added.
        jdbc.update("DELETE FROM rail_published WHERE date < ? AND date IN (SELECT date FROM rail_train_day)", today);
        jdbc.update("DELETE FROM rail_train_day WHERE date IN (SELECT date FROM rail_published)");
        int days = jdbc.update("""
                INSERT INTO rail_train_day (date, train_id)
                SELECT DISTINCT p.date, n.id
                FROM rail_published p JOIN rail_keyed k ON k.service_id = p.service_id
                     JOIN rail_train n ON n.key = k.key""");
        // Versions of a train no day has any more: its days were published anew.
        jdbc.update("DELETE FROM rail_train n WHERE NOT EXISTS (SELECT 1 FROM rail_train_day d WHERE d.train_id = n.id)");
        log.info("Train timetables kept: {} new trains, {} trains on days from {}", trains, days, today);
    }
}
