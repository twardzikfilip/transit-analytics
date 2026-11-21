package com.transitanalytics.recording;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.zbiorkom.FeedPosition;
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

        PositionHistoryDto.FleetSeries all = history.fleet(FROM, TO, null, "BKM");
        assertThat(all.stepSeconds()).as("one poll per point in an hour's window").isEqualTo(10);
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

        assertThat(history.fleet(FROM, TO, "IT-N1", "BKM").points()).hasSize(1);
        assertThat(history.routes(FROM, TO, "BKM")).extracting(PositionHistoryDto.RouteActivity::route)
                .containsExactly("IT-10", "IT-N1");

    }

    @Test
    void aVehicleIsRunningUntilAMinuteWithoutReports() {
        long a = vehicle("IT-R1");
        long b = vehicle("IT-R2");
        // Polls every 20 s. b reported before the window, and a has no new report in two polls (the feed skipped it):
        // both still count as running. At 10:01:20, a minute after a's last report, a has stopped.
        poll("09:59:40", position(b, "09:59:40", "IT-10", 0L));
        poll("10:00:00", position(a, "10:00:00", "IT-10", 0L));
        poll("10:00:20", position(a, "10:00:20", "IT-10", 0L));
        poll("10:00:40", position(b, "10:00:40", "IT-10", 0L));
        poll("10:01:00", position(b, "10:01:00", "IT-10", 0L));
        poll("10:01:20", position(b, "10:01:20", "IT-10", 0L));

        PositionHistoryDto.FleetSeries fleet = history.fleet(FROM, TO, null, "BKM");
        assertThat(fleet.points()).extracting(PositionHistoryDto.FleetPoint::vehicles)
                .containsExactly(2.0, 2.0, 2.0, 2.0, 1.0);
        assertThat(fleet.summary().samples()).as("reports before the window are left out").isEqualTo(5);
        assertThat(fleet.summary().latestVehicles()).isEqualTo(1);
        assertThat(fleet.summary().peakVehicles()).isEqualTo(2);
    }

    @Test
    void groupsDelaysIntoBands() {
        long a = vehicle("IT-D1");
        long b = vehicle("IT-D2");
        poll("10:00:00", position(a, "10:00:01", "IT-10", -45L), position(b, "10:00:02", "IT-10", 119L));
        poll("10:01:00", position(a, "10:01:01", "IT-10", 120L), position(b, "10:01:02", "IT-10", 299L));
        poll("10:02:00", position(a, "10:02:01", "IT-10", 599L), position(b, "10:02:02", "IT-10", 600L));
        poll("10:03:00", position(a, "10:03:01", "IT-10", 3600L), position(b, "10:03:02", "IT-10", null));

        List<PositionHistoryDto.DelayBand> bands = history.delays(FROM, TO, null, "BKM");

        assertThat(bands).containsExactly(
                new PositionHistoryDto.DelayBand(null, 2, 2, 2),  // -45 s and 119 s: on time
                new PositionHistoryDto.DelayBand(2, 5, 2, 2),     // 120 s and 299 s
                new PositionHistoryDto.DelayBand(5, 10, 1, 1),    // 599 s
                new PositionHistoryDto.DelayBand(10, null, 2, 2)); // 600 s and 1 h
        assertThat(history.delays(FROM, TO, "other line", "BKM")).allSatisfy(band -> assertThat(band.reports()).isZero());
    }

    @Test
    void leavesOutVehiclesOfOtherOperators() {
        long city = vehicle("IT-B1");
        long regional = vehicle("IT-R1", "WSCHODEXPRESS");
        long unknown = vehicle("IT-U1", null);
        poll("10:00:00", position(city, "10:00:10", "IT-10", 60L), position(regional, "10:00:20", "IT-453", 3000L),
                position(unknown, "10:00:30", "IT-X", 0L));

        PositionHistoryDto.FleetSeries fleet = history.fleet(FROM, TO, null, "BKM");
        assertThat(fleet.summary().vehicles()).isEqualTo(1);
        assertThat(fleet.points()).extracting(PositionHistoryDto.FleetPoint::vehicles).containsExactly(1.0);
        assertThat(history.routes(FROM, TO, "BKM")).extracting(PositionHistoryDto.RouteActivity::route).containsExactly("IT-10");
        assertThat(history.delays(FROM, TO, null, "BKM").stream().mapToLong(PositionHistoryDto.DelayBand::reports).sum())
                .isEqualTo(1);
        assertThat(history.fleet(FROM, TO, "IT-453", "BKM").points()).as("a regional line, even when asked for").isEmpty();

        // Another operator, or all of them.
        assertThat(history.fleet(FROM, TO, null, "WSCHODEXPRESS").summary().vehicles()).isEqualTo(1);
        assertThat(history.routes(FROM, TO, "WSCHODEXPRESS")).extracting(PositionHistoryDto.RouteActivity::route)
                .containsExactly("IT-453");
        assertThat(history.fleet(FROM, TO, null, null).summary().vehicles()).isEqualTo(3);
        assertThat(history.delays(FROM, TO, null, null).stream().mapToLong(PositionHistoryDto.DelayBand::reports).sum())
                .isEqualTo(3);
        assertThat(history.agencies()).startsWith("BKM").contains("WSCHODEXPRESS");
    }

    private void poll(String time, FeedPosition... positions) {
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

    private static FeedPosition position(long vehicleId, String time, String route, Long delay) {
        return new FeedPosition(vehicleId, "x", "BUS", "BKM", null, route, null, "IT-01", "Test", 53.13, 23.16,
                90.0, delay, Instant.parse("2099-01-01T" + time + "Z"), List.of());
    }
}
