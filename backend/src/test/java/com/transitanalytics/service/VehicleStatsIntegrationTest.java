package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.VehicleStatsDto;
import com.transitanalytics.recording.PositionSampleWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorded positions of three vehicles on Monday 5 January 2099 (UTC+1) against a timetable; rolled back.
 * Brigade IT7-01 runs 06:00–08:20 without a break, IT7-02 runs 06:00–06:40 and, after a break, 09:00–09:40.
 */
@DbIntegrationTest
@Transactional
class VehicleStatsIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired
    VehicleStatsService stats;

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void measuresUseLinesAndVehiclesLeavingTheirBrigade() {
        var timetable = new TimetableFixture(jdbc, "IT_VS");
        long it7 = timetable.route("IT-V7", "IT7");
        timetable.stop("IT-V1", "Rynek", null, 53.1);
        timetable.stop("IT-V2", "Dworzec", null, 53.2);
        timetable.trip(it7, "IT7-01_IT_VS_1", 0, "Dworzec", null, "06:00", 40, "IT-V1", "IT-V2");
        timetable.trip(it7, "IT7-01_IT_VS_2", 1, "Rynek", null, "06:50", 40, "IT-V2", "IT-V1");
        timetable.trip(it7, "IT7-01_IT_VS_3", 0, "Dworzec", null, "07:40", 40, "IT-V1", "IT-V2");
        timetable.trip(it7, "IT7-02_IT_VS_1", 0, "Dworzec", null, "06:00", 40, "IT-V1", "IT-V2");
        timetable.trip(it7, "IT7-02_IT_VS_2", 1, "Rynek", null, "09:00", 40, "IT-V2", "IT-V1");

        long a = vehicle("IT-VA", "Solaris Urbino 12", "BKM");
        long b = vehicle("IT-VB", "Yutong U12", "BKM");
        long c = vehicle("IT-VC", "MAN Lion's City", "IT-OTHER");
        // A leaves IT7-01 at 06:30, mid-run; B takes it over from 06:50.
        record(a, "IT7", "IT7-01", "06:00", "06:30");
        record(b, "IT7", "IT7-01", "06:50", "08:20");
        // C runs IT7-02 until its break (no incident), comes back, leaves it at 09:10 for line IT8.
        record(c, "IT7", "IT7-02", "06:00", "06:35");
        record(c, "IT7", "IT7-02", "09:00", "09:10");
        record(c, "IT8", "IT8-01", "09:20", "09:40");
        // D ran IT7 the day before and not since; E has never reported.
        long idle = vehicle("IT-VD", "Solaris Urbino 18", "IT-OTHER");
        vehicle("IT-VE", null, "IT-OTHER");
        record(idle, "IT7", "IT7-01", local("17:00").minus(Duration.ofDays(1)), local("17:30").minus(Duration.ofDays(1)));

        Instant from = Instant.parse("2099-01-05T04:00:00Z");
        Instant to = Instant.parse("2099-01-05T10:00:00Z");
        VehicleStatsDto.Report report = stats.report(TimeWindow.DAY, from, to, Set.of());

        assertThat(report.vehicles()).isEqualTo(3);
        assertThat(report.agencies()).containsExactly(new VehicleStatsDto.Agency("BKM", 2),
                new VehicleStatsDto.Agency("IT-OTHER", 1));
        assertThat(report.mostUsed()).extracting(u -> u.vehicle().number() + " " + u.hours())
                .containsExactly("IT-VB 1.5", "IT-VC 1.1", "IT-VA 0.5");
        assertThat(report.mostUsed().get(0).vehicle().model()).isEqualTo("Yutong U12");
        assertThat(report.leastUsed().get(0).vehicle().number()).isEqualTo("IT-VA");
        assertThat(report.mostLines().get(0).lines()).containsExactly("IT7", "IT8");

        assertThat(report.multiLineDays()).singleElement().satisfies(d -> {
            assertThat(d.day()).isEqualTo(DAY);
            assertThat(d.vehicle().number()).isEqualTo("IT-VC");
            assertThat(d.lines()).extracting(VehicleStatsDto.LineSpell::line).containsExactly("IT7", "IT8");
        });

        assertThat(report.incidents()).extracting(i -> i.vehicle().number() + " " + i.kind())
                .containsExactly("IT-VC switched", "IT-VA disappeared");
        VehicleStatsDto.Incident disappeared = report.incidents().get(1);
        assertThat(disappeared.replacedBy().number()).isEqualTo("IT-VB");
        assertThat(disappeared.scheduledUntil()).as("no break: the whole day").isEqualTo(local("08:20"));
        assertThat(disappeared.missedRuns()).as("06:50–07:30 and 07:40–08:20; it nearly finished its first run").isEqualTo(2);
        assertThat(report.incidents().get(0).nextLine()).isEqualTo("IT8");

        // Only BKM's vehicles: C (IT-OTHER) drops out of every table, the operators to choose from stay.
        VehicleStatsDto.Report bkm = stats.report(TimeWindow.DAY, from, to, Set.of("BKM"));
        assertThat(bkm.vehicles()).isEqualTo(2);
        assertThat(bkm.agencies()).hasSize(2);
        assertThat(bkm.mostUsed()).extracting(u -> u.vehicle().number()).containsExactly("IT-VB", "IT-VA");
        assertThat(bkm.mostLines()).extracting(u -> u.vehicle().number()).containsExactly("IT-VB", "IT-VA");
        assertThat(bkm.multiLineDays()).isEmpty();
        assertThat(bkm.incidents()).extracting(i -> i.vehicle().number()).containsExactly("IT-VA");
        VehicleStatsDto.Report other = stats.report(TimeWindow.DAY, from, to, Set.of("IT-OTHER"));
        assertThat(other.incidents()).extracting(i -> i.vehicle().number()).containsExactly("IT-VC");

        // Buses not in service in the window (the database's own fleet too, all seen before 2099).
        assertThat(other.unused()).extracting(u -> u.vehicle().number()).containsExactly("IT-VD", "IT-VE");
        assertThat(other.unused().get(0).lastLine()).isEqualTo("IT7");
        assertThat(other.unused().get(0).lastSeen()).isEqualTo(local("17:30").minus(Duration.ofDays(1)));
        assertThat(other.unused().get(1).lastSeen()).isNull();
        assertThat(report.unused()).extracting(u -> u.vehicle().number()).doesNotContain("IT-VA", "IT-VB", "IT-VC")
                .contains("IT-VD", "IT-VE");
    }

    private long vehicle(String number, String model, String agency) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency, model) VALUES (?, 'BUS', ?, ?) RETURNING id",
                Long.class, number, agency, model);
    }

    /** A report every minute from {@code from} to {@code to} (local time), on time. */
    private void record(long vehicle, String line, String brigade, String from, String to) {
        record(vehicle, line, brigade, local(from), local(to));
    }

    private void record(long vehicle, String line, String brigade, Instant from, Instant to) {
        for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(60)) {
            writer.write(List.of(new LivePositionDto(vehicle, "x", "BUS", "BKM", null, line, null, brigade, "Test",
                    53.13, 23.16, 90.0, 0L, t, List.of(), null)), t);
        }
    }

    private static Instant local(String hhmm) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }
}
