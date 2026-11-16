package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.LineStatsDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Line statistics from a small weekday timetable of 2099; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class LineStatsIntegrationTest {

    @Autowired
    LineStatsService lineStats;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reportsTheWeekdayFiguresOfEachLine() {
        var timetable = new TimetableFixture(jdbc, "IT_LS");
        long route = timetable.route("IT-55", "55");
        for (int i = 0; i < 4; i++) {
            timetable.stop("IT-L" + i, "L" + i + "(7" + i + ")", "1", 53.10 + i * 0.01);
        }
        // Brigade 01: two runs out (10 and 12 min) and one back, all in the morning peak (06:30–08:30);
        // 4 km each way.
        timetable.trip(route, "IT-55-01_1", 0, "L3", "IT-LS0", "06:30", 10, "IT-L0:0", "IT-L1:1", "IT-L2:3", "IT-L3:4");
        timetable.trip(route, "IT-55-01_2", 1, "L0", "IT-LS1", "06:45", 10, "IT-L3:0", "IT-L2:1", "IT-L1:3", "IT-L0:4");
        timetable.trip(route, "IT-55-01_3", 0, "L3", "IT-LS0", "07:30", 12, "IT-L0:0", "IT-L1:1", "IT-L2:3", "IT-L3:4");

        LineStatsDto.Report report = lineStats.weekdayReport();

        assertThat(report.serviceId()).isEqualTo("IT_LS");
        assertThat(report.peak()).extracting(p -> p.from() + "–" + p.to())
                .containsExactly("06:30–08:30", "15:00–17:00");
        assertThat(report.lines()).singleElement().satisfies(line -> {
            assertThat(line.route()).isEqualTo("55");
            assertThat(line.lengthKm()).isEqualTo(4.0);
            assertThat(line.shortestTravelSeconds()).isEqualTo(600);
            assertThat(line.longestTravelSeconds()).isEqualTo(720);
            // 12 km in 32 minutes.
            assertThat(line.peakSpeedKmh()).isEqualTo(22.5);
            // Peak is 240 minutes: 2 departures one way (120 min), 1 the other (240 min), averaged.
            assertThat(line.peakIntervalMinutes()).isEqualTo(180.0);
            assertThat(line.offPeakIntervalMinutes()).isNull();
            assertThat(line.peakVehicles()).isEqualTo(1);
            assertThat(line.offPeakVehicles()).isZero();
            assertThat(line.trips()).isEqualTo(3);
        });
    }
}
