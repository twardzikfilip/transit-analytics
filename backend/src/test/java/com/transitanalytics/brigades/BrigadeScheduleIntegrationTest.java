package com.transitanalytics.brigades;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.dto.BrigadeDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** A brigade running two routes on the weekdays of 2099; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class BrigadeScheduleIntegrationTest {

    @Autowired
    BrigadeScheduleService schedules;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listsTheBrigadesRunsOfTheServiceDayIncludingAfterMidnight() {
        var timetable = new TimetableFixture(jdbc, "IT_BR");
        long a = timetable.route("IT-BA", "IT9");
        long b = timetable.route("IT-BB", "IT8");
        timetable.stop("IT-B1", "Rynek (101)", null, 53.1);
        timetable.stop("IT-B2", "Dworzec", null, 53.2);
        timetable.trip(a, "IT9-01_IT_BR_2", 1, "Rynek", null, "23:50", 50, "IT-B2", "IT-B1");
        timetable.trip(a, "IT9-01_IT_BR_1", 0, "Dworzec", null, "06:00", 20, "IT-B1", "IT-B2");
        timetable.trip(b, "IT9-01_IT_BR_3", 0, "Dworzec", null, "25:00", 10, "IT-B1", "IT-B2");
        timetable.trip(a, "IT9-02_IT_BR_1", 0, "Dworzec", null, "07:00", 20, "IT-B1", "IT-B2");

        // 00:10 on Tuesday 6 January in Warsaw (UTC+1) still belongs to Monday's service day.
        BrigadeDto.Schedule schedule = schedules.schedule("IT9-01", Instant.parse("2099-01-05T23:10:00Z"));

        assertThat(schedule.serviceDay()).isEqualTo(LocalDate.of(2099, 1, 5));
        assertThat(schedule.runs()).extracting(BrigadeDto.Run::route).containsExactly("IT9", "IT9", "IT8");
        BrigadeDto.Run first = schedule.runs().get(0);
        assertThat(first.from()).isEqualTo("Rynek");
        assertThat(first.to()).isEqualTo("Dworzec");
        assertThat(first.departure()).isEqualTo(Instant.parse("2099-01-05T05:00:00Z"));
        assertThat(first.arrival()).isEqualTo(Instant.parse("2099-01-05T05:20:00Z"));
        assertThat(schedule.runs().get(2).departure()).as("25:00 is 01:00 the next morning")
                .isEqualTo(Instant.parse("2099-01-06T00:00:00Z"));

        assertThat(schedules.schedule("IT9-01", Instant.parse("2099-01-10T10:00:00Z")).runs())
                .as("no service on Saturday").isEmpty();
        assertThat(schedules.schedule("nope", Instant.parse("2099-01-05T10:00:00Z")).runs()).isEmpty();
    }
}
