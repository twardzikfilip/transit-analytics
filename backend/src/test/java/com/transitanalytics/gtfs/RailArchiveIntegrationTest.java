package com.transitanalytics.gtfs;

import com.transitanalytics.DbIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DbIntegrationTest
@Transactional
class RailArchiveIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long route;

    @BeforeEach
    void timetable() {
        for (String table : List.of("trip", "route", "agency", "stop", "calendar_date", "assumed_timetable_day")) {
            jdbc.update("DELETE FROM " + table + " WHERE feed = 'rail'");
        }
        jdbc.update("DELETE FROM rail_train");
        long agency = jdbc.queryForObject("INSERT INTO agency (feed, gtfs_id, name, url, timezone) "
                + "VALUES ('rail', 'PR', 'P', 'https://example.org', 'Europe/Warsaw') RETURNING id", Long.class);
        route = jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, agency_id, route_type) "
                + "VALUES ('rail', 'REG', ?, 2) RETURNING id", Long.class, agency);
        jdbc.update("INSERT INTO stop (feed, gtfs_id, name, parent_station) VALUES ('rail', 'A', 'A', NULL), "
                + "('rail', 'A-1', 'A', 'A'), ('rail', 'B', 'B', NULL)");
        load("S1", "08:00:00", "2099-11-25", "2099-11-26", "2099-11-27");
    }

    @Test
    void keepsPastDaysAsTheyWereAndRenewsTheOthers() {
        archive("2099-11-25").afterImport(new GtfsImported("rail", Instant.EPOCH));
        assertThat(days()).containsExactly("2099-11-25 08:00:00", "2099-11-26 08:00:00", "2099-11-27 08:00:00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rail_train", Long.class)).as("stored once").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT station_id || ' ' || stop_id FROM rail_train_call ORDER BY stop_sequence",
                String.class)).containsExactly("A A-1", "B B");

        // The next day's file: from the 26th, the train leaves later, under another service id.
        load("S9", "08:05:00", "2099-11-25", "2099-11-26", "2099-11-28");
        archive("2099-11-26").afterImport(new GtfsImported("rail", Instant.EPOCH));
        assertThat(days()).containsExactly("2099-11-25 08:00:00", "2099-11-26 08:05:00", "2099-11-27 08:00:00",
                "2099-11-28 08:05:00");
    }

    @Test
    void doesNotKeepAssumedDaysAndDropsVersionsNoDayHas() {
        jdbc.update("INSERT INTO assumed_timetable_day (feed, date) VALUES ('rail', '2099-11-27')");
        archive("2099-11-25").atStartup();
        assertThat(days()).containsExactly("2099-11-25 08:00:00", "2099-11-26 08:00:00");

        load("S1", "09:00:00", "2099-11-25", "2099-11-26");
        archive("2099-11-25").afterImport(new GtfsImported("bialystok", Instant.EPOCH));
        assertThat(days()).as("another feed").containsExactly("2099-11-25 08:00:00", "2099-11-26 08:00:00");
        archive("2099-11-25").atStartup();
        assertThat(days()).containsExactly("2099-11-25 09:00:00", "2099-11-26 09:00:00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rail_train", Long.class)).isEqualTo(1);
    }

    private RailArchive archive(String today) {
        return new RailArchive(jdbc, Clock.fixed(Instant.parse(today + "T04:00:00Z"), ZoneOffset.UTC));
    }

    private List<String> days() {
        return jdbc.queryForList("SELECT d.date || ' ' || c.departure_time FROM rail_train_day d JOIN rail_train_call c "
                + "ON c.train_id = d.train_id AND c.stop_sequence = 0 ORDER BY 1", String.class);
    }

    /** Replaces the timetable with one train, as an import does. */
    private void load(String service, String departure, String... dates) {
        jdbc.update("DELETE FROM trip WHERE feed = 'rail'");
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'rail'");
        long trip = jdbc.queryForObject("INSERT INTO trip (feed, gtfs_id, route_id, service_id, short_name) "
                + "VALUES ('rail', 'T1', ?, ?, '10419') RETURNING id", Long.class, route, service);
        String call = "INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time) VALUES "
                + "(?, (SELECT id FROM stop WHERE feed = 'rail' AND gtfs_id = ?), ?, ?, ?)";
        jdbc.update(call, trip, "A-1", 0, departure, departure);
        jdbc.update(call, trip, "B", 1, "10:00:00", "10:00:00");
        for (String date : dates) {
            jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) "
                    + "VALUES ('rail', ?, ?::date, 1)", service, date);
        }
    }
}
