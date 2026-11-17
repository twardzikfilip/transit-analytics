package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.recording.PositionSampleWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buses recorded on 5 January 2099 on a line with stops IT-DG A–D (longitude 23.6, away from the real network),
 * reporting every 20 s with their next stop and delay; rolled back.
 */
@DbIntegrationTest
@Transactional
class DelayGenerationIntegrationTest {

    private static final double LON = 23.6;
    private static final Instant FROM = Instant.parse("2099-01-05T04:00:00Z");
    private static final Instant TO = Instant.parse("2099-01-05T12:00:00Z");

    @Autowired
    DelayGenerationService delays;

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void measuresTheDelayGeneratedBetweenStops() {
        var timetable = new TimetableFixture(jdbc, "IT_DG");
        timetable.route("IT-DG", "IT-DG");
        String[] names = {"IT-DG A", "IT-DG B NŻ", "IT-DG C", "IT-DG D"};
        for (int i = 0; i < names.length; i++) {
            jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                    "IT-DG" + i, "IT-DG" + i, names[i] + " (90" + i + ")", 53.60 + i * 0.003, LON);
        }
        // Six buses: the delay on leaving A, B and C (the stretch B–C costs a minute), and they skip nothing.
        for (int bus = 0; bus < 6; bus++) {
            drive(vehicle("IT-DG" + bus), "IT-DG", "07:%02d:00".formatted(bus * 5), 0, 0, 0, 20, 20, 20, 80, 80);
        }
        // A bus whose next stop jumps from B to D between two reports: C wasn't seen being passed.
        long skipping = vehicle("IT-DGS");
        report(skipping, "IT-DG", local("09:00:00"), 1, 0);
        report(skipping, "IT-DG", local("09:00:20"), 2, 0);
        report(skipping, "IT-DG", local("09:00:40"), 4, 300);
        // A bus of a line not in the timetable.
        drive(vehicle("IT-DGX"), "IT-X7", "08:00:00", 0, 0, 0, 0, 0, 0, 0, 0);

        DelayGenerationDto.Report report = delays.report(TimeWindow.DAY, FROM, TO);

        assertThat(report.days()).isEqualTo(1);
        assertThat(report.runs()).as("the skipping bus has two passages: no run").isEqualTo(6);
        assertThat(report.stretches()).extracting(s -> s.from() + " → " + s.to() + " " + s.meanSeconds().get(0))
                .containsExactly("IT-DG A → IT-DG B 20.0", "IT-DG B → IT-DG C 60.0");
        DelayGenerationDto.Stretch bc = report.stretches().get(1);
        assertThat(bc.fromLat()).as("the timetable stop, matched without NŻ and its code").isEqualTo(53.603);
        assertThat(bc.runs().get(2)).as("morning peak").isEqualTo(6);
        assertThat(report.lines()).as("runs of 3 stops are too short for a gain from first stop to last").isEmpty();

        DelayGenerationDto.LineReport line = delays.line(TimeWindow.DAY, FROM, TO, "IT-DG");
        assertThat(line.directions()).singleElement().satisfies(d -> {
            assertThat(d.headsign()).isEqualTo("IT-DG D");
            assertThat(d.stretches()).extracting(DelayGenerationDto.LineStretch::medianSeconds)
                    .containsExactly(20, 60);
            assertThat(d.stretches().get(1).byHour().get(7)).isEqualTo(60.0);
            assertThat(d.stretches().get(1).exampleIds()).hasSize(3);
        });
        assertThat(line.examples()).hasSize(3).allSatisfy(e -> {
            assertThat(e.stops()).extracting(DelayGenerationDto.ExampleStop::delaySeconds)
                    .containsExactly(0, 20, 80);
            assertThat(e.brigade()).isEqualTo("IT-DG-01");
        });
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    private static Instant local(String hhmmss) {
        return ServiceDay.gtfsTimeZero(LocalDate.of(2099, 1, 5)).plusSeconds(LocalTime.parse(hhmmss).toSecondOfDay());
    }

    /**
     * Reports every 20 s from {@code start}: next stop A for the first two, then B, C, D in turn (two or three
     * reports each), with these delays.
     */
    private void drive(long vehicle, String line, String start, int... delays) {
        int[] nextStop = {1, 1, 2, 2, 2, 3, 3, 4};
        Instant t = local(start);
        for (int i = 0; i < delays.length; i++) {
            report(vehicle, line, t, nextStop[i], delays[i]);
            t = t.plusSeconds(20);
        }
    }

    private void report(long vehicle, String line, Instant t, int nextStop, int delay) {
        String name = "IT-DG " + (char) ('A' + nextStop - 1);
        writer.write(List.of(new LivePositionDto(vehicle, "x", "BUS", "BKM", null, line, null, line + "-01",
                "IT-DG D", 53.60 + (nextStop - 1) * 0.003 - 0.001, LON, 0.0, (long) delay, t,
                List.of(new LivePositionDto.UpcomingStop(nextStop, name, null, null)), null)), t);
    }
}
