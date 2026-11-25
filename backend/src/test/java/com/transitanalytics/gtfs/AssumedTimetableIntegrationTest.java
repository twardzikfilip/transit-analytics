package com.transitanalytics.gtfs;

import com.transitanalytics.DbIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Feed "it" has a service per date, Monday 16 to Saturday 28 November 2099's dates named after them. Rolled back. */
@DbIntegrationTest
@Transactional
class AssumedTimetableIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void timetable() {
        long route = jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, long_name, route_type)"
                + " VALUES ('it', 'IT', 'IT', 3) RETURNING id", Long.class);
        jdbc.update("""
                INSERT INTO trip (feed, gtfs_id, route_id, service_id)
                SELECT 'it', 'IT-' || d::date, ?, d::date::text
                FROM generate_series(date '2099-11-16', date '2099-11-28', interval '1 day') d""", route);
        calendar();
    }

    private void calendar() {
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'it'");
        jdbc.update("""
                INSERT INTO calendar_date (feed, service_id, date, exception_type)
                SELECT 'it', d::date::text, d::date, 1
                FROM generate_series(date '2099-11-16', date '2099-11-28', interval '1 day') d""");
    }

    private AssumedTimetable on(String day) {
        var feeds = new GtfsProperties(null, null, null, null,
                Map.of("it", new GtfsProperties.Feed("https://example.org/it.zip", "Other", "ITX")), "-");
        return new AssumedTimetable(jdbc, feeds, Clock.fixed(Instant.parse(day + "T12:00:00Z"), ZoneOffset.UTC));
    }

    private String service(String day) {
        List<String> services = jdbc.queryForList(
                "SELECT service_id FROM calendar_date WHERE feed = 'it' AND date = ?::date", String.class, day);
        return services.isEmpty() ? null : String.join(",", services);
    }

    private List<LocalDate> assumed() {
        return jdbc.queryForList("SELECT date FROM assumed_timetable_day WHERE feed = 'it' ORDER BY date",
                LocalDate.class);
    }

    @Test
    void makesNothingUpWhileTheTimetableRunsOn() {
        on("2099-11-21").nightly();
        assertThat(assumed()).isEmpty();
    }

    @Test
    void continuesWithTheSameWeekdaysOfTheLastKnownWeek() {
        on("2099-11-25").atStartup();
        assertThat(assumed()).containsExactly(LocalDate.of(2099, 11, 29), LocalDate.of(2099, 11, 30),
                LocalDate.of(2099, 12, 1), LocalDate.of(2099, 12, 2));
        assertThat(service("2099-11-28")).isEqualTo("2099-11-28");
        assertThat(service("2099-11-29")).isEqualTo("2099-11-22");
        assertThat(service("2099-11-30")).isEqualTo("2099-11-23");

        // The weeks after follow the same known week, not each other.
        on("2099-12-10").nightly();
        assertThat(service("2099-12-13")).isEqualTo("2099-11-22");
        assertThat(service("2099-12-12")).isEqualTo("2099-11-28");
        assertThat(service("2099-12-17")).isEqualTo("2099-11-26");
        assertThat(service("2099-12-18")).isNull();
        assertThat(assumed()).hasSize(19);

        // At most eight weeks.
        on("2100-06-01").nightly();
        assertThat(assumed()).hasSize(AssumedTimetable.MAX_DAYS);
    }

    @Test
    void anImportOfTheSameFileContinuesItAgainAndANewOneReplacesIt() {
        on("2099-12-02").nightly();
        calendar();
        assertThat(service("2099-12-02")).isNull();
        on("2099-12-02").imported("it");
        assertThat(service("2099-12-02")).isEqualTo("2099-11-25");

        // A new file from 1 December on: the days it has aren't assumed any more, the two before it stay marked.
        calendar();
        jdbc.update("""
                INSERT INTO calendar_date (feed, service_id, date, exception_type)
                SELECT 'it', '2099-11-28', d::date, 1
                FROM generate_series(date '2099-12-01', date '2099-12-14', interval '1 day') d""");
        on("2099-12-02").imported("it");
        assertThat(service("2099-12-02")).isEqualTo("2099-11-28");
        assertThat(assumed()).containsExactly(LocalDate.of(2099, 11, 29), LocalDate.of(2099, 11, 30));
    }

    @Test
    void leavesAFeedThatIsNotConfiguredOrNotLoadedAlone() {
        on("2099-12-02").imported("bialystok");
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'it'");
        on("2099-12-02").imported("it");
        assertThat(assumed()).isEmpty();
    }
}
