package com.transitanalytics.corridors;

import com.transitanalytics.data.Shapes;
import com.transitanalytics.corridors.CorridorBuilder.TripStops;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stretches between stops with their buses and shapes, for the corridor maps. */
class SegmentLoadsTest {
    private final CorridorFixtures fixtures = new CorridorFixtures();

    @Test
    void segmentLoadsCountPeakBusesBothDirectionsAndFollowTheShape() {
        // Shape "s": points every 0.25 km; trip km at stops are 0, 0.5, 1.0 (see fixtures.trip()).
        var shape = new ArrayList<Shapes.MeasuredPoint>();
        for (int i = 0; i <= 4; i++) {
            shape.add(new Shapes.MeasuredPoint(53.0 + i * 0.001, 23.0, i * 0.25));
        }
        var trips = List.of(
                withShape(fixtures.trip("A", 7 * 60, 1, 2, 3)),          // 07:00: peak
                withShape(fixtures.trip("B", 12 * 60, 1, 2, 3)),         // noon: off-peak
                fixtures.trip("A", 16 * 60, 103, 102, 101));             // way back at 16:00: peak, no shape
        var loads = SegmentLoads.segmentLoads(trips, CorridorFixtures.stops(), Map.of("s", shape));

        assertThat(loads).hasSize(2);
        var first = loads.stream().filter(l -> l.from().equals("S1")).findFirst().orElseThrow();
        assertThat(first.to()).isEqualTo("S2");
        assertThat(first.buses()).isEqualTo(3);
        assertThat(first.peakBuses()).isEqualTo(2);
        // Stop S1, the shape point at 0.25 km, stop S2.
        assertThat(first.points()).hasSize(3);
        assertThat(first.points().get(1)).containsExactly(53.001, 23.0);
    }

    private static TripStops withShape(TripStops t) {
        return new TripStops(t.id(), t.route(), t.stops(), t.departures(), t.km(), "s");
    }
}
