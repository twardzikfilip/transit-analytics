package com.transitanalytics.classification;

import com.transitanalytics.domain.RouteCapacity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteCapacityClassifierTest {

    private final RouteCapacityClassifier classifier =
            new RouteCapacityClassifier(ClassificationConfigs.routes().capacity());

    /** Departures every {@code minutes} from {@code from} to {@code to} (hours), in seconds after midnight. */
    private static List<Integer> every(int minutes, int from, int to) {
        List<Integer> times = new ArrayList<>();
        for (int t = from * 3600; t <= to * 3600; t += minutes * 60) {
            times.add(t);
        }
        return times;
    }

    private RouteCapacity classify(List<List<Integer>> departures, Double weekday, Double saturday, Double sunday) {
        return classifier.classify(classifier.inputs(departures, weekday, saturday, sunday));
    }

    @Test
    void measuresHeadwaysTripsAndHowEvenTheGapsAre() {
        var in = classifier.inputs(List.of(every(10, 5, 23), every(12, 5, 23)), 0.8, null, 0.2);
        // Peak: 4 hours; 24 and 20 departures. A day: 109 and 91.
        assertThat(in.peakHeadway()).isCloseTo(11.0, within(0.01));
        assertThat(in.averageHeadway()).isCloseTo(11.0, within(0.01));
        assertThat(in.tripsPerDirection()).isEqualTo(100.0);
        assertThat(in.headwayCv()).isZero();
        assertThat(in.megaWeekday()).isEqualTo(0.8);
        assertThat(in.megaSaturday()).isNull();

        var uneven = classifier.inputs(List.of(List.of(6 * 3600, 7 * 3600, 13 * 3600, 22 * 3600)), null, null, null);
        assertThat(uneven.headwayCv()).isGreaterThan(0.6);
        assertThat(uneven.peakHeadway()).isEqualTo(240.0);
    }

    @Test
    void classesLinesByTheirBusesAndFrequency() {
        var frequent = List.of(every(10, 5, 23), every(10, 5, 23));
        // MEGA every day and every 10 min: main.
        assertThat(classify(frequent, 0.9, 0.7, 0.6)).isEqualTo(RouteCapacity.MAIN);
        // A day type it doesn't run on doesn't count against it.
        assertThat(classify(frequent, 0.9, null, null)).isEqualTo(RouteCapacity.MAIN);
        // MEGA only on weekdays: primary.
        assertThat(classify(frequent, 0.9, 0.0, 0.0)).isEqualTo(RouteCapacity.PRIMARY);
        var sixteen = List.of(every(16, 5, 23), every(16, 5, 23));
        assertThat(classify(sixteen, 0.3, 0.0, 0.0)).isEqualTo(RouteCapacity.PRIMARY);
        // Up to under 20 min at peak (lines 1-4 run every 17-19 min).
        var nineteen = List.of(every(19, 5, 23), every(19, 5, 23));
        assertThat(classify(nineteen, 0.8, 0.0, 0.0)).isEqualTo(RouteCapacity.PRIMARY);
        assertThat(classify(List.of(every(21, 5, 23)), 0.8, 0.0, 0.0)).isEqualTo(RouteCapacity.SECONDARY);
        // No MEGA buses: secondary, however frequent; a MEGA line too rare for primary too.
        assertThat(classify(frequent, 0.0, 0.0, 0.0)).isEqualTo(RouteCapacity.SECONDARY);
        assertThat(classify(List.of(every(30, 5, 23)), 1.0, 1.0, 1.0)).isEqualTo(RouteCapacity.SECONDARY);
        // Hourly or rarer: tertiary when regular, irregular when not.
        assertThat(classify(List.of(every(70, 5, 23)), 0.0, 0.0, 0.0)).isEqualTo(RouteCapacity.TERTIARY);
        assertThat(classify(List.of(List.of(6 * 3600, 7 * 3600, 13 * 3600, 22 * 3600)), 0.0, null, null))
                .isEqualTo(RouteCapacity.IRREGULAR);
        assertThat(classify(List.of(List.of(7 * 3600)), null, null, null)).isEqualTo(RouteCapacity.IRREGULAR);
    }

    @Test
    void nightLinesGetNoCapacityClass() {
        assertThat(ClassificationConfigs.routes().capacity().skipSubtypes())
                .containsExactly(com.transitanalytics.domain.RouteSubtype.NIGHT_URBAN);
    }

    @Test
    void anyMegaConditionCanBeUsed() {
        var rules = new RouteClassProperties.Capacity(List.of(), 28, 0.5, 0.1, List.of("06:30-08:30"), List.of(
                new RouteClassProperties.Rule(RouteCapacity.SECONDARY, RouteClassProperties.Mega.NONE, null, null,
                        null, null),
                new RouteClassProperties.Rule(RouteCapacity.PRIMARY, RouteClassProperties.Mega.ANY, null, null,
                        null, null)), RouteCapacity.IRREGULAR, java.util.Map.of());
        var custom = new RouteCapacityClassifier(rules);
        var in = custom.inputs(List.of(every(10, 6, 9)), 0.0, null, null);
        assertThat(custom.classify(in)).isEqualTo(RouteCapacity.SECONDARY);
        assertThat(custom.classify(custom.inputs(List.of(every(10, 6, 9)), 0.5, null, null)))
                .isEqualTo(RouteCapacity.PRIMARY);
    }
}
