package com.transitanalytics.recording;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.PositionHistoryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Queries over position_sample on the real database; the test data is rolled back. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false",
        "app.recording.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class PositionHistoryIntegrationTest {

    private static final Instant FROM = Instant.parse("2099-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2099-01-01T11:00:00Z");

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    PositionHistoryService history;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void countsVehiclesPerPollPeakLatestAndDistinct() {
        long a = vehicle("IT-H1");
        long b = vehicle("IT-H2");
        long c = vehicle("IT-H3");
        // Three polls: a and b running; a, b and c (peak); then only c (a and b went off duty).
        poll("10:00:00", position(a, "10:00:10", "IT-10", 60L), position(b, "10:00:40", "IT-10", 120L));
        poll("10:01:00", position(a, "10:01:10", "IT-10", 90L), position(b, "10:01:30", "IT-N1", null),
                position(c, "10:01:20", "IT-10", 600L));
        poll("10:05:00", position(c, "10:05:05", "IT-10", -30L));

        PositionHistoryDto.FleetSeries all = history.fleet(FROM, TO, null);
        assertThat(all.stepSeconds()).isEqualTo(60);
        assertThat(all.points()).extracting(PositionHistoryDto.FleetPoint::vehicles).containsExactly(2.0, 3.0, 1.0);
        assertThat(all.points().get(2).time()).as("polls 10:02–10:04 are missing, not zero")
                .isEqualTo(Instant.parse("2099-01-01T10:05:00Z"));
        assertThat(all.points().get(1).meanDelaySeconds()).as("mean of the reports with a delay")
                .isEqualTo(345.0);

        PositionHistoryDto.Summary summary = all.summary();
        assertThat(summary.vehicles()).as("different vehicles seen").isEqualTo(3);
        assertThat(summary.peakVehicles()).as("most at once").isEqualTo(3);
        assertThat(summary.latestVehicles()).isEqualTo(1);
        assertThat(summary.latestPoll()).isEqualTo(Instant.parse("2099-01-01T10:05:00Z"));
        assertThat(summary.samples()).isEqualTo(6);

        assertThat(history.fleet(FROM, TO, "IT-N1").points()).hasSize(1);
        assertThat(history.routes(FROM, TO)).extracting(PositionHistoryDto.RouteActivity::route)
                .containsExactly("IT-10", "IT-N1");
        assertThat(history.samples(FROM, TO, "IT-10", null, 0, 2, null, false).getTotalElements()).isEqualTo(5);
        assertThat(history.samples(FROM, TO, null, "IT-H2", 0, 10, null, false).getTotalElements()).isEqualTo(2);

        // Sorting: by delay (unknown delays last), by line (naturally), newest first by default.
        assertThat(history.samples(FROM, TO, null, null, 0, 10, "delay,desc", false).getContent())
                .extracting(PositionHistoryDto.Sample::delaySeconds).containsExactly(600, 120, 90, 60, -30, null);
        assertThat(history.samples(FROM, TO, null, null, 0, 10, "delay,asc", false).getContent())
                .extracting(PositionHistoryDto.Sample::delaySeconds).containsExactly(-30, 60, 90, 120, 600, null);
        assertThat(history.samples(FROM, TO, null, null, 0, 1, null, false).getContent().get(0).observedAt())
                .isEqualTo(Instant.parse("2099-01-01T10:05:05Z"));
    }

    @Test
    void groupsDelaysIntoMinuteBands() {
        long a = vehicle("IT-D1");
        long b = vehicle("IT-D2");
        poll("10:00:00", position(a, "10:00:01", "IT-10", -45L), position(b, "10:00:02", "IT-10", 59L));
        poll("10:01:00", position(a, "10:01:01", "IT-10", 60L), position(b, "10:01:02", "IT-10", 119L));
        poll("10:02:00", position(a, "10:02:01", "IT-10", 599L), position(b, "10:02:02", "IT-10", 600L));
        poll("10:03:00", position(a, "10:03:01", "IT-10", 3600L), position(b, "10:03:02", "IT-10", null));

        List<PositionHistoryDto.DelayBand> bands = history.delays(FROM, TO, null);

        assertThat(bands).hasSize(11);
        assertThat(bands.get(0)).isEqualTo(new PositionHistoryDto.DelayBand(null, 1, 2, 2)); // -45 s and 59 s
        assertThat(bands.get(1)).isEqualTo(new PositionHistoryDto.DelayBand(1, 2, 2, 2));    // 60 s and 119 s
        assertThat(bands.get(9)).isEqualTo(new PositionHistoryDto.DelayBand(9, 10, 1, 1));   // 599 s
        assertThat(bands.get(10)).isEqualTo(new PositionHistoryDto.DelayBand(10, null, 2, 2)); // 600 s and 1 h
        assertThat(bands.subList(2, 9)).allSatisfy(band -> assertThat(band.reports()).isZero());
        assertThat(history.delays(FROM, TO, "other line")).allSatisfy(band -> assertThat(band.reports()).isZero());

        // The 1-hour delay is an outlier (over 30 min); excluding outliers keeps reports without a delay.
        assertThat(history.samples(FROM, TO, null, null, 0, 20, null, false).getTotalElements()).isEqualTo(8);
        assertThat(history.samples(FROM, TO, null, null, 0, 20, null, true).getContent())
                .extracting(PositionHistoryDto.Sample::delaySeconds).doesNotContain(3600).contains((Integer) null)
                .hasSize(7);
    }

    private void poll(String time, LivePositionDto... positions) {
        writer.write(List.of(positions), Instant.parse("2099-01-01T" + time + "Z"));
    }

    private long vehicle(String number) {
        return jdbc.queryForObject("INSERT INTO vehicle (vehicle_number, type) VALUES (?, 'BUS') RETURNING id",
                Long.class, number);
    }

    private static LivePositionDto position(long vehicleId, String time, String route, Long delay) {
        return new LivePositionDto(vehicleId, "x", "BUS", "BKM", null, route, null, "IT-01", "Test", 53.13, 23.16,
                90.0, delay, Instant.parse("2099-01-01T" + time + "Z"), List.of());
    }
}
