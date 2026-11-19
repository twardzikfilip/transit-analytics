package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.buslanes.BusLaneProperties;
import com.transitanalytics.buslanes.BusLanes;
import com.transitanalytics.dto.BusLaneDto;
import com.transitanalytics.zbiorkom.FeedPosition;
import com.transitanalytics.recording.PositionSampleWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Buses recorded on Monday 5 January 2099 around a made-up eastbound bus lane along 53.115 N, 23.175-23.185 E (in
 * the city, 240 m from the nearest terminus); rolled back.
 */
@DbIntegrationTest
@Transactional
class BusLaneReportIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);
    private static final double LAT = 53.115;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    PositionSampleWriter writer;

    @TempDir
    Path dir;

    @Test
    void comparesSpeedsAndDelaysOnTheLaneTheOtherWayAndElsewhere() throws Exception {
        Path file = dir.resolve("bus-lanes.json");
        Files.writeString(file, """
                {"source":"test","fetchedAt":"2099-01-01T00:00:00Z","lanes":[{"id":7,"name":"Testowa",
                 "highway":"secondary","forward":1,"backward":0,"points":[[53.115,23.175],[53.115,23.185]]}]}""");
        BusLanes lanes = new BusLanes(new BusLaneProperties(file.toString(), "-", List.of(), null, 1, 15, 45, null),
                RestClient.create(), JsonMapper.builder().build(), Clock.systemUTC());
        var service = new BusLaneReportService(jdbc, transaction, lanes, Clock.systemUTC());
        new TimetableFixture(jdbc, "IT_BL").route("IT-B1", "IT-B1");

        // East on the lane at 07:00 (peak): 67 m every 20 s, 12 km/h, 10 s more late each time.
        drive(vehicle("IT-BA"), "IT-B1", "07:00:00", LAT, 23.176, 0.001, 0, 120, 10, 10);
        // West the other way, 10 m off, without a lane, twice as fast and making up time.
        drive(vehicle("IT-BB"), "IT-B1", "07:00:00", LAT + 0.00009, 23.184, -0.002, 0, 120, -5, 4);
        // North elsewhere at 10:00 (off-peak), 222 m every 20 s (40 km/h); and a bus of a line not in the timetable.
        drive(vehicle("IT-BC"), "IT-B1", "10:00:00", 53.12, 23.20, 0, 0.002, 0, 0, 6);
        drive(vehicle("IT-BX"), "IT-X9", "10:00:00", 53.12, 23.21, 0, 0.002, 0, 0, 6);

        BusLaneDto.Report report = service.compute(new ReportWindow(Instant.parse("2099-01-05T04:00:00Z"),
                Instant.parse("2099-01-05T12:00:00Z")), lanes.current());

        assertThat(report.days()).isEqualTo(1);
        BusLaneDto.Figures lane = report.onLanes();
        assertThat(lane.km()).isEqualTo(0.6);
        assertThat(lane.speed()).isCloseTo(12.0, within(0.2));
        assertThat(lane.peakSpeed()).isEqualTo(lane.speed());
        assertThat(lane.offPeakSpeed()).isNull();
        assertThat(lane.delayPerKm()).isCloseTo(150, within(2.0));
        assertThat(report.sameStreetsWithoutLane().km()).isEqualTo(0.4);
        assertThat(report.sameStreetsWithoutLane().speed()).as("under 0.5 km: not given").isNull();
        assertThat(report.elsewhere().km()).as("the other way, and north elsewhere").isEqualTo(1.5);
        assertThat(report.elsewhere().offPeakSpeed()).isCloseTo(40.0, within(0.5));
        assertThat(report.stretches()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Testowa");
            assertThat(s.heading()).isEqualTo("eastbound");
            assertThat(s.lines()).containsExactly("IT-B1");
            assertThat(s.figures().km()).isEqualTo(0.6);
            assertThat(s.opposite()).isNotNull();
            assertThat(s.opposite().km()).isEqualTo(0.4);
        });
        assertThat(report.hours()).extracting(BusLaneDto.Hour::hour).containsExactly(7, 10);
        assertThat(report.hours().get(0).laneSpeed()).isCloseTo(12.0, within(0.2));
        // Asked again for the same window: the same report.
        ReportWindow window = new ReportWindow(Instant.parse("2099-01-05T04:00:00Z"),
                Instant.parse("2099-01-05T12:00:00Z"));
        assertThat(service.report(window)).isSameAs(service.report(window));
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    /** {@code reports} reports 20 s apart from {@code start} (local time), each moved by dLon / dLat. */
    private void drive(long vehicle, String line, String start, double lat, double lon, double dLon, double dLat,
                       long delay, long dDelay, int reports) {
        Instant t = ServiceDay.gtfsTimeZero(DAY).plusSeconds(LocalTime.parse(start).toSecondOfDay());
        for (int i = 0; i < reports; i++) {
            writer.write(List.of(new FeedPosition(vehicle, "x", "BUS", "BKM", null, line, null, line + "-01",
                    "Test", lat + i * dLat, lon + i * dLon, null, delay + i * dDelay, t, List.of())), t);
            t = t.plusSeconds(20);
        }
    }
}
