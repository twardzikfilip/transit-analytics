package com.transitanalytics.routes;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.dto.RouteStatsDto;
import com.transitanalytics.timetable.Periods;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Route statistics from a small weekday timetable of 2099; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class RouteStatsIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    @Autowired
    RouteStatsService routeStats;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reportsTheWeekdayFiguresOfEachRoute() {
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

        RouteStatsDto.Report report = routeStats.weekdayReport();

        assertThat(report.serviceId()).isEqualTo("IT_LS");
        assertThat(report.peak()).extracting(p -> p.from() + "–" + p.to())
                .containsExactly("06:30–08:30", "15:00–17:00");
        assertThat(report.routes()).singleElement().satisfies(stats -> {
            assertThat(stats.route()).isEqualTo("55");
            assertThat(stats.lengthKm()).isEqualTo(4.0);
            // 12 km in 32 minutes.
            assertThat(stats.peakSpeedKmh()).isEqualTo(22.5);
            // Peak is 240 minutes: 2 departures one way (120 min), 1 the other (240 min), averaged.
            assertThat(stats.peakIntervalMinutes()).isEqualTo(180.0);
            assertThat(stats.offPeakIntervalMinutes()).isNull();
            assertThat(stats.peakVehicles()).isEqualTo(1);
            assertThat(stats.trips()).isEqualTo(3);
            // No recordings of route 55.
            assertThat(stats.peakDelaySeconds()).isNull();
        });
    }

    @Test
    void averagesEachRoutesRecordedDelayInPeakAndOffPeakHoursOnWeekdays() {
        // A weekday after the aggregate's materialised part, so the rows just added are read as they are.
        LocalDate weekday = LocalDate.now(ZONE).plusDays(2);
        while (weekday.getDayOfWeek().getValue() > 5) weekday = weekday.plusDays(1);
        LocalDate weekend = weekday.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY));
        bus = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-D1', 'BUS', 'BKM') RETURNING id",
                Long.class);
        sample(weekday, "07:10", 120);
        sample(weekday, "07:40", 180);
        sample(weekday, "16:20", 150);
        sample(weekday, "10:05", -30);
        sample(weekday, "18:30", 30);
        // Not counted: a weekend, and the night.
        sample(weekend, "07:10", 900);
        sample(weekday, "23:10", 900);

        Double[] delays = routeStats.recordedDelays().get("IT-D1");

        assertThat(delays).containsExactly(150.0, 0.0);
    }

    @Test
    void anHourBelongsToThePartOfTheDayItsMiddleFallsIn() {
        assertThat(RouteStatsService.hours(Periods.PEAK)).isEqualTo("6, 7, 15, 16");
        assertThat(RouteStatsService.hours(Periods.OFF_PEAK)).isEqualTo("8, 9, 10, 11, 12, 13, 14, 17, 18, 19");
        assertThat(RouteStatsService.hours(java.util.List.of())).isEqualTo("-1");
    }

    private long bus;

    private void sample(LocalDate day, String time, int delay) {
        var at = LocalDateTime.of(day, LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime();
        jdbc.update("""
                INSERT INTO position_sample (observed_at, vehicle_id, route_name, brigade, latitude, longitude,
                                             delay_seconds)
                VALUES (?, ?, 'IT-D1', 'IT-D1-01', 53.1, 23.1, ?)""", at, bus, delay);
    }
}
