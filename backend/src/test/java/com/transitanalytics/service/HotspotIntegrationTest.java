package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.recording.PositionSampleWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buses recorded on 5 January 2099 along a line running north at longitude 23.5 (away from the real network), with
 * stops at 53.50 (terminal), 53.52 and 53.54 (terminal); rolled back.
 */
@DbIntegrationTest
@Transactional
class HotspotIntegrationTest {

    private static final double LON = 23.5;
    /** Traffic lights between the stops. */
    private static final double LIGHTS = 53.508;

    @Autowired
    HotspotService hotspots;

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void findsWhereBusesWaitAwayFromStops() {
        var timetable = new TimetableFixture(jdbc, "IT_HS");
        long route = timetable.route("IT-H1", "IT-H1");
        stop("IT-HS1", "Południe(901)", 53.50);
        stop("IT-HS2", "Środek(902)", 53.52);
        stop("IT-HS3", "Północ(903)", 53.54);
        timetable.trip(route, "IT-H1-01_IT_HS_1", 0, "Północ", null, "06:00", 20, "IT-HS1", "IT-HS2", "IT-HS3");

        long[] buses = {vehicle("IT-HA"), vehicle("IT-HB"), vehicle("IT-HC")};
        for (int run = 0; run < 2; run++) {
            for (int b = 0; b < buses.length; b++) {
                // Waits 40 s at the lights (three reports), then 40 s at the middle stop.
                drive(buses[b], "IT-H1", "07:%02d:00".formatted(run * 20 + b * 5),
                        53.505, LIGHTS, LIGHTS, LIGHTS, 53.515, 53.52, 53.52, 53.52, 53.525);
            }
        }
        // A bus of a line not in the timetable waiting at the lights, and one parked there for 10 minutes.
        drive(vehicle("IT-HX"), "IT-X9", "08:00:00", 53.505, LIGHTS, LIGHTS, LIGHTS, 53.515);
        double[] parked = new double[32];
        java.util.Arrays.fill(parked, LIGHTS + 0.0001);
        drive(vehicle("IT-HD"), "IT-H1", "09:00:00", parked);

        HotspotDto.Report report = hotspots.report(TimeWindow.DAY, Instant.parse("2099-01-05T04:00:00Z"),
                Instant.parse("2099-01-05T12:00:00Z"), null);

        assertThat(report.lines()).containsExactly("IT-H1");
        assertThat(report.days()).isEqualTo(1);
        assertThat(report.standstills()).as("only the waits at the lights").isEqualTo(6);
        assertThat(report.hotspots()).singleElement().satisfies(h -> {
            assertThat(h.lat()).isCloseTo(LIGHTS, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(h.standstills()).isEqualTo(6);
            assertThat(h.vehicles()).isEqualTo(3);
            assertThat(h.medianSeconds()).isEqualTo(40);
            assertThat(h.hours()).isEqualTo(0.07);
            assertThat(h.minutesPerDay()).as("one recorded day").isEqualTo(4.0);
            assertThat(h.nearestStop()).isEqualTo("Południe");
            assertThat(h.lines()).extracting(HotspotDto.LineTime::line).containsExactly("IT-H1");
            assertThat(h.approaches()).extracting(HotspotDto.Approach::heading).containsExactly("N");
            assertThat(h.peakHour()).isEqualTo(7);
        });
        assertThat(report.hotspotHours()).isEqualTo(0.1);

        assertThat(hotspots.report(TimeWindow.DAY, Instant.parse("2099-01-05T04:00:00Z"),
                Instant.parse("2099-01-05T12:00:00Z"), "IT-X9").hotspots()).isEmpty();
    }

    private void stop(String gtfsId, String name, double latitude) {
        jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                gtfsId, gtfsId, name, latitude, LON);
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    /** A report every 20 s from {@code start} (local time), heading north, at each latitude in turn. */
    private void drive(long vehicle, String line, String start, double... latitudes) {
        Instant t = ServiceDay.gtfsTimeZero(java.time.LocalDate.of(2099, 1, 5))
                .plusSeconds(java.time.LocalTime.parse(start).toSecondOfDay());
        for (double lat : latitudes) {
            writer.write(List.of(new LivePositionDto(vehicle, "x", "BUS", "BKM", null, line, null, line + "-01",
                    "Test", lat, LON, 0.0, 0L, t, List.of(), null)), t);
            t = t.plusSeconds(20);
        }
    }
}
