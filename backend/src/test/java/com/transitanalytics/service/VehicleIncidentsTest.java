package com.transitanalytics.service;

import com.transitanalytics.service.VehicleIncidents.Missed;
import com.transitanalytics.service.VehicleIncidents.Run;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleIncidentsTest {

    /** 06:00–06:40, 06:50–07:30, a break, 09:00–09:40. */
    private static final List<Run> RUNS = List.of(run("06:00", "06:40"), run("06:50", "07:30"), run("09:00", "09:40"));

    @Test
    void leavingMidRunMissesTheRestOfTheBlockUntilTheBreak() {
        assertThat(VehicleIncidents.missed(RUNS, t("06:20"))).isEqualTo(new Missed(t("07:30"), t("09:40"), 2));
        assertThat(VehicleIncidents.missed(RUNS, t("09:10"))).isEqualTo(new Missed(t("09:40"), t("09:40"), 1));
    }

    @Test
    void leavingAtABreakAtTheEndOrBeforeTheFirstRunIsScheduled() {
        assertThat(VehicleIncidents.missed(RUNS, t("07:20"))).as("near the end of the run before the break").isNull();
        assertThat(VehicleIncidents.missed(RUNS, t("08:00"))).as("in the break").isNull();
        assertThat(VehicleIncidents.missed(RUNS, t("09:30"))).as("near the end of the day").isNull();
        assertThat(VehicleIncidents.missed(RUNS, t("05:00"))).as("before the first run").isNull();
        assertThat(VehicleIncidents.missed(List.of(), t("08:00"))).isNull();
    }

    @Test
    void aShortLayoverIsNotABreak() {
        // 06:40–06:50 is a 10-minute layover: the vehicle was still due to run 06:50–07:30.
        assertThat(VehicleIncidents.missed(RUNS, t("06:45"))).isEqualTo(new Missed(t("07:30"), t("09:40"), 1));
    }

    @Test
    void needsRecordingToGoOnAfterTheLastReport() {
        Instant last = Instant.parse("2026-11-16T10:00:00Z");
        assertThat(VehicleIncidents.recordedAfter(last, last.plusSeconds(15 * 60))).isTrue();
        assertThat(VehicleIncidents.recordedAfter(last, last.plusSeconds(5 * 60))).isFalse();
    }

    private static Run run(String from, String to) {
        return new Run(t(from), t(to));
    }

    private static int t(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 3600 + Integer.parseInt(hhmm.substring(3)) * 60;
    }
}
