package com.transitanalytics.timetable;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.dto.TimetableDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** The Stops and Stop times lists: filters, order and pages; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class StopTimetableIntegrationTest {

    @Autowired
    StopTimetable timetable;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void filtersStopsByTextAndZoneInPages() {
        var fixture = new TimetableFixture(jdbc, "IT_STOPS");
        fixture.stop("IT-S1", "Itq_b", "2", 53.1);
        fixture.stop("IT-S2", "Itq_a", "2", 53.2);
        fixture.stop("IT-S3", "ItqXa", "1", 53.3);

        assertThat(timetable.stops("itq", null, 0, 10).content()).as("case-insensitive, by name")
                .extracting(TimetableDto.Stop::name).containsExactly("ItqXa", "Itq_a", "Itq_b");
        // "_" is a literal underscore, not the SQL wildcard: "q_a" doesn't match "ItqXa".
        assertThat(timetable.stops("q_a", null, 0, 10).content()).extracting(TimetableDto.Stop::name)
                .containsExactly("Itq_a");
        assertThat(timetable.stops("itq", "2", 0, 10).page().totalElements()).isEqualTo(2);
        var second = timetable.stops("itq", null, 1, 2);
        assertThat(second.content()).extracting(TimetableDto.Stop::name).containsExactly("Itq_b");
        assertThat(second.page().totalPages()).isEqualTo(2);
    }

    @Test
    void listsATripsCallsInOrderAndAStopsByDeparture() {
        var fixture = new TimetableFixture(jdbc, "IT_STOPS");
        long route = fixture.route("IT-R1", "ITR1");
        fixture.stop("IT-T0", "Tee Zero", null, 53.1);
        fixture.stop("IT-T1", "Tee One", null, 53.2);
        long late = fixture.trip(route, "IT-R1-2", 0, "North", null, "07:00", 5, "IT-T0", "IT-T1");
        long early = fixture.trip(route, "IT-R1-1", 0, "North", null, "06:00", 5, "IT-T0", "IT-T1");

        assertThat(timetable.stopTimes(late, null, 0, 10).content()).extracting(TimetableDto.StopTime::stopName)
                .containsExactly("Tee Zero", "Tee One");
        long stop = jdbc.queryForObject("SELECT id FROM stop WHERE gtfs_id = 'IT-T0'", Long.class);
        var calls = timetable.stopTimes(null, stop, 0, 10).content();
        assertThat(calls).extracting(TimetableDto.StopTime::tripId).containsExactly(early, late);
        assertThat(calls.get(0).routeName()).isEqualTo("ITR1");
        assertThat(calls.get(0).departureTime()).isEqualTo("06:00:00");
    }
}
