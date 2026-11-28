package com.transitanalytics.timetable;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.transitanalytics.timetable.Periods.OFF_PEAK;
import static com.transitanalytics.timetable.Periods.PEAK;
import static org.assertj.core.api.Assertions.assertThat;

class TimetableMathTest {

    private static int at(int h, int m) {
        return h * 3600 + m * 60;
    }

    @Test
    void periodsAreTheSameEverywhere() {
        assertThat(Periods.toDto(PEAK)).extracting(p -> p.from() + "–" + p.to())
                .containsExactly("06:30–08:30", "15:00–17:00");
        assertThat(Periods.toDto(OFF_PEAK)).extracting(p -> p.from() + "–" + p.to())
                .containsExactly("08:30–15:00", "17:00–20:00");
    }

    @Test
    void intervalIsPeriodMinutesPerDepartureAveragedOverDirections() {
        // Direction 0 every 15 min in both peak windows (240 min): 8 + 8 departures → 15 min.
        // Direction 1 every 30 min: 4 + 4 → 30 min. Average: 22.5 min.
        List<Integer> every15 = new ArrayList<>();
        List<Integer> every30 = new ArrayList<>();
        for (int m = 0; m < 120; m += 15) {
            every15.add(at(6, 30 + m));
            every15.add(at(15, m));
        }
        for (int m = 0; m < 120; m += 30) {
            every30.add(at(6, 30 + m));
            every30.add(at(15, m));
        }
        assertThat(TimetableMath.interval(Map.of(0, every15, 1, every30), PEAK)).isEqualTo(22.5);
        // Off-peak (08:30–15:00 and 17:00–20:00 = 570 min): 6 departures in the evening → 95 min.
        List<Integer> evening = new ArrayList<>();
        for (int m = 0; m < 180; m += 30) {
            evening.add(at(17, m));
        }
        assertThat(TimetableMath.interval(Map.of(0, evening), OFF_PEAK)).isEqualTo(95.0);
        assertThat(TimetableMath.interval(Map.of(0, List.of(at(12, 0))), PEAK)).as("no peak departures").isNull();
    }

    @Test
    void ordersRoutesNaturally() {
        assertThat(List.of("N1", "100", "2", "10").stream().sorted(TimetableMath.ROUTE_ORDER))
                .containsExactly("2", "10", "100", "N1");
    }

    @Test
    void mostFrequentTakesTheSmallestOnATieAndNullLast() {
        assertThat(TimetableMath.mostFrequent(List.of("b", "a", "b", "a"))).isEqualTo("a");
        assertThat(TimetableMath.mostFrequent(java.util.Arrays.asList(null, "x"))).isEqualTo("x");
    }

    @Test
    void regularityIsTheCoefficientOfVariationOfGaps() {
        // Every 10 min from 06:30 to 08:20: perfectly even.
        List<Integer> even = new ArrayList<>();
        for (int m = 0; m < 120; m += 10) {
            even.add(at(6, 30 + m));
        }
        assertThat(TimetableMath.regularity(Map.of(0, even), PEAK)).isEqualTo(0.0);

        // Gaps 5, 15, 5, 15 min: mean 10, population sd 5 → CV 0.5.
        List<Integer> bunched = List.of(at(6, 30), at(6, 35), at(6, 50), at(6, 55), at(7, 10));
        assertThat(TimetableMath.regularity(Map.of(0, bunched), PEAK)).isEqualTo(0.5);
    }

    @Test
    void regularityNeedsThreeGapsAndIgnoresTheBreakBetweenWindows() {
        assertThat(TimetableMath.regularity(Map.of(0, List.of(at(6, 30), at(6, 40), at(6, 50))), PEAK)).isNull();
        // Every 20 min in the morning and in the afternoon: the 08:10 → 15:00 break isn't a gap.
        List<Integer> twoPeaks = new ArrayList<>();
        for (int m = 0; m < 120; m += 20) {
            twoPeaks.add(at(6, 30 + m));
            twoPeaks.add(at(15, m));
        }
        assertThat(TimetableMath.regularity(Map.of(0, twoPeaks), PEAK)).isEqualTo(0.0);
    }

    @Test
    void regularityWeighsDirectionsByTheirNumberOfGaps() {
        // Direction 0: 8 gaps, CV 0 (even). Direction 1: 4 gaps 5,15,5,15 → CV 0.5. Weighted: 0.5 × 4 / 12 = 0.17.
        List<Integer> even = new ArrayList<>();
        for (int m = 0; m <= 80; m += 10) {
            even.add(at(6, 30 + m));
        }
        List<Integer> bunched = List.of(at(7, 0), at(7, 5), at(7, 20), at(7, 25), at(7, 40));
        assertThat(TimetableMath.regularity(Map.of(0, even, 1, bunched), PEAK)).isEqualTo(0.17);
    }
}
