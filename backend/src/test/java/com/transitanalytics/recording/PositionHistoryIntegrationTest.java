package com.transitanalytics.recording;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.PositionHistoryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Queries over position_sample on the real database; the test data is rolled back. */
@DbIntegrationTest
@Transactional
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

    @Test
    void leavesOutVehiclesOfOtherOperators() {
        long city = vehicle("IT-B1");
        long regional = vehicle("IT-R1", "WSCHODEXPRESS");
        long unknown = vehicle("IT-U1", null);
        poll("10:00:00", position(city, "10:00:10", "IT-10", 60L), position(regional, "10:00:20", "IT-453", 3000L),
                position(unknown, "10:00:30", "IT-X", 0L));

        PositionHistoryDto.FleetSeries fleet = history.fleet(FROM, TO, null);
        assertThat(fleet.summary().vehicles()).isEqualTo(1);
        assertThat(fleet.points()).extracting(PositionHistoryDto.FleetPoint::vehicles).containsExactly(1.0);
        assertThat(history.routes(FROM, TO)).extracting(PositionHistoryDto.RouteActivity::route).containsExactly("IT-10");
        assertThat(history.delays(FROM, TO, null).stream().mapToLong(PositionHistoryDto.DelayBand::reports).sum())
                .isEqualTo(1);
        assertThat(history.samples(FROM, TO, null, null, 0, 10, null, false).getContent())
                .extracting(PositionHistoryDto.Sample::vehicleNumber).containsExactly("IT-B1");
        assertThat(history.fleet(FROM, TO, "IT-453").points()).as("a regional line, even when asked for").isEmpty();
    }

    @Test
    void replaysPositionsAsFramesPerPollFilteredByLines() {
        long a = vehicle("IT-P1");
        long b = vehicle("IT-P2");
        long regional = vehicle("IT-P3", "WSCHODEXPRESS");
        poll("10:00:00", position(a, "10:00:10", "IT-10", 60L), position(b, "10:00:20", "IT-20", 700L),
                position(regional, "10:00:30", "IT-453", 0L));
        // Two reports of vehicle a in the same poll minute: the later one is used.
        poll("10:01:00", position(a, "10:01:05", "IT-10", 90L), position(b, "10:01:10", "IT-20", null));
        poll("10:01:30", position(a, "10:01:40", "IT-10", 120L));

        PositionHistoryDto.Replay all = history.replay(FROM, TO, null);
        assertThat(all.stepSeconds()).isEqualTo(60);
        assertThat(all.vehicles()).containsExactly("IT-P1", "IT-P2");
        assertThat(all.lines()).containsExactly("IT-10", "IT-20");
        assertThat(all.frames()).extracting(PositionHistoryDto.Frame::time)
                .containsExactly(Instant.parse("2099-01-01T10:00:00Z"), Instant.parse("2099-01-01T10:01:00Z"));
        assertThat(all.frames().get(0).positions()).containsExactly(
                List.of(0, 53.13, 23.16, 60, 0), List.of(1, 53.13, 23.16, 700, 1));
        assertThat(all.frames().get(1).positions()).containsExactly(
                List.of(0, 53.13, 23.16, 120, 0), java.util.Arrays.asList(1, 53.13, 23.16, null, 1));

        PositionHistoryDto.Replay one = history.replay(FROM, TO, List.of("IT-20", " ", "IT-99"));
        assertThat(one.routes()).containsExactly("IT-20", "IT-99");
        assertThat(one.vehicles()).containsExactly("IT-P2");
        assertThat(one.frames()).hasSize(2);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> history.replay(FROM, FROM.plus(java.time.Duration.ofDays(8)), null))
                .hasMessageContaining("at most 7 days");
    }

    private void poll(String time, LivePositionDto... positions) {
        writer.write(List.of(positions), Instant.parse("2099-01-01T" + time + "Z"));
    }

    private long vehicle(String number) {
        return vehicle(number, "BKM");
    }

    private long vehicle(String number, String agency) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', ?) RETURNING id",
                Long.class, number, agency);
    }

    private static LivePositionDto position(long vehicleId, String time, String route, Long delay) {
        return new LivePositionDto(vehicleId, "x", "BUS", "BKM", null, route, null, "IT-01", "Test", 53.13, 23.16,
                90.0, delay, Instant.parse("2099-01-01T" + time + "Z"), List.of());
    }
}
