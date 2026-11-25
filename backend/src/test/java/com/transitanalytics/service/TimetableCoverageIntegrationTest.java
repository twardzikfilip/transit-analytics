package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.TimetableCoverageDto;
import com.transitanalytics.gtfs.GtfsProperties;
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

/** Feed "it" runs until Thursday 31 December 2099; "none" was never imported. Rolled back. */
@DbIntegrationTest
@Transactional
class TimetableCoverageIntegrationTest {

    private static final LocalDate LAST = LocalDate.of(2099, 12, 31);

    @Autowired
    JdbcTemplate jdbc;

    private TimetableCoverageDto at(String instant) {
        var feeds = new GtfsProperties(null, null, null, new GtfsProperties.Feed("https://example.org/main.zip", null, null),
                Map.of("none", new GtfsProperties.Feed("https://example.org/n.zip", "None", null),
                        "it", new GtfsProperties.Feed("https://example.org/it.zip", "Other", "ITX")), "-");
        return new TimetableCoverageService(jdbc, feeds, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).coverage();
    }

    @Test
    void warnsWhenAFeedIsAboutToEndHasEndedOrIsNotLoaded() {
        var timetable = new TimetableFixture(jdbc, "IT_TC");
        timetable.stop("IT-TC1", "One", null, 53.1);
        timetable.stop("IT-TC2", "Two", null, 53.2);
        timetable.trip(timetable.route("IT-TC", "IT-TC"), "IT-TC-1", 0, "Two", null, "07:00", 10, "IT-TC1", "IT-TC2");
        jdbc.update("UPDATE calendar_date SET feed = 'it' WHERE service_id = 'IT_TC'");
        jdbc.update("UPDATE trip SET feed = 'it' WHERE service_id = 'IT_TC'");
        // A service without trips covers nothing.
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) VALUES ('it', 'EMPTY', '2100-06-01', 1)");

        TimetableCoverageDto early = at("2099-12-25T12:00:00Z");
        assertThat(early.feeds()).extracting(TimetableCoverageDto.Feed::feed).containsExactly("bialystok", "it", "none");
        assertThat(early.feeds().get(1)).isEqualTo(new TimetableCoverageDto.Feed("it", LAST, 6, false, List.of()));
        assertThat(early.feeds().get(2)).isEqualTo(new TimetableCoverageDto.Feed("none", null, null, true, List.of()));
        assertThat(at("2099-12-26T12:00:00Z").feeds().get(1)).isEqualTo(new TimetableCoverageDto.Feed("it", LAST, 5, true, List.of()));
        // 00:30 local time still belongs to the day before.
        assertThat(at("2099-12-31T23:30:00Z").feeds().get(1).daysLeft()).isZero();
        assertThat(at("2100-01-02T12:00:00Z").feeds().get(1)).isEqualTo(new TimetableCoverageDto.Feed("it", LAST, -2, true, List.of()));
    }
}
