package com.transitanalytics.dwell;

import com.transitanalytics.data.Report;
import com.transitanalytics.data.ReportPair;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.dwell.StopDwells.Hour;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StopDwellsTest {

    static final Instant T0 = Instant.parse("2026-11-27T07:00:00Z");
    /** Metres north per degree of latitude. */
    static final double DEG = 1 / 111_320.0;
    static final double LAT = 53.13;
    static final double LON = 23.16;

    /** A route with one stop along the way, at (LAT, LON), and another 1 km north. */
    static final Map<String, List<Timetables.Stop>> STOPS = Map.of("9", List.of(
            new Timetables.Stop("Rynek", LAT, LON), new Timetables.Stop("Lipowa", LAT + 1000 * DEG, LON)));

    /** A bus's reports every 10 s, at these metres north of the stop. */
    static List<ReportPair> drive(long bus, String route, Instant start, double... metres) {
        List<ReportPair> pairs = new ArrayList<>();
        for (int i = 1; i < metres.length; i++) {
            pairs.add(new ReportPair(report(bus, route, start.plusSeconds(10L * (i - 1)), metres[i - 1]),
                    report(bus, route, start.plusSeconds(10L * i), metres[i]), null, null));
        }
        return pairs;
    }

    static Report report(long bus, String route, Instant at, double north) {
        return new Report(bus, "B" + bus, at, route, "9-01", "X", 0, 1, LAT + north * DEG, LON);
    }

    static List<Hour> measure(List<ReportPair> pairs) {
        StopDwells dwells = new StopDwells(STOPS);
        pairs.forEach(dwells.taker());
        return dwells.hours();
    }

    @Test
    void measuresEachVisitAsATripsStopIs() {
        List<ReportPair> pairs = new ArrayList<>();
        // Bus 1 pulls in, stands two steps (0 → 1 → 0 m, 20 s), pulls out: 20 s halted.
        pairs.addAll(drive(1, "9", T0, -150, -80, -30, 0, 1, 0, 30, 100, 200));
        // Bus 2 goes past at speed: a visit of 0 s.
        pairs.addAll(drive(2, "9", T0, -300, -150, 0, 150, 300));
        // Bus 3 on another route, near the same place: not along its route, not counted.
        pairs.addAll(drive(3, "27", T0, -80, 0, 0, 0, 80));

        assertThat(measure(pairs)).containsExactly(new Hour(T0, "9", "Rynek", 2, 20));
    }

    @Test
    void leavesOutLongBreaksAndUnclearVisits() {
        // Standing 11 minutes: a break.
        double[] standing = new double[68];
        assertThat(measure(drive(1, "9", T0, standing))).isEmpty();
        // Crawling past (between 7 and 12 km/h): can't be told.
        assertThat(measure(drive(2, "9", T0, -60, -30, 0, 30, 60))).isEmpty();
    }

    @Test
    void ranksStopsByMeanDwellOverEnoughVisits() {
        List<Hour> hours = List.of(
                new Hour(T0, "9", "Rynek", 15, 450), new Hour(T0, "27", "Rynek", 10, 300),
                new Hour(T0, "9", "Lipowa", 30, 300), new Hour(T0, "9", "Rzadka", 5, 1000));

        // All routes: Rynek's visits on both routes together (25, 30 s each); Rzadka has too few.
        assertThat(StopDwellService.rank(hours, null, null))
                .containsExactly(new PositionHistoryDto.StopDwell("Rynek", 30, 25),
                        new PositionHistoryDto.StopDwell("Lipowa", 10, 30));
        // One route: only its visits (Rynek's 15 on route 9 are too few).
        assertThat(StopDwellService.rank(hours, "9", null)).extracting(PositionHistoryDto.StopDwell::stop)
                .containsExactly("Lipowa");
        // An operator's routes.
        assertThat(StopDwellService.rank(hours, null, Set.of("27"))).isEmpty();
    }
}
