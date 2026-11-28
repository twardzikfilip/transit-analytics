package com.transitanalytics.buslanes;

import com.transitanalytics.geo.Geo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BusLaneMatcherTest {

    /** Eastbound along 53.13 N from 23.15 to 23.16 E (about 670 m); and a two-way section further north. */
    private static final List<BusLane> LANES = List.of(
            new BusLane(1, "Lipowa", "secondary", 1, 0, List.of(new double[] {53.13, 23.15},
                    new double[] {53.13, 23.155}, new double[] {53.13, 23.16})),
            new BusLane(2, "Sienkiewicza", "secondary", 1, 1, List.of(new double[] {53.14, 23.15},
                    new double[] {53.14, 23.16})));
    private final BusLaneMatcher matcher = new BusLaneMatcher(LANES, 15, 45);

    @Test
    void matchesABusNearALaneHeadingItsWay() {
        // On the lane heading east, and 10 m north of it heading a little north of east.
        assertThat(matcher.match(53.13, 23.152, 90)).isEqualTo(new BusLaneMatcher.Match(0, true));
        assertThat(matcher.match(53.13009, 23.157, 60)).isEqualTo(new BusLaneMatcher.Match(0, true));
        // Heading west: no lane that way; 25 m off: too far, unless asked to look further.
        assertThat(matcher.match(53.13, 23.152, 270)).isNull();
        assertThat(matcher.match(53.130225, 23.152, 90)).isNull();
        assertThat(matcher.match(53.130225, 23.152, 90, 30)).isEqualTo(new BusLaneMatcher.Match(0, true));
        // Past its end; and the two-way section both ways.
        assertThat(matcher.match(53.13, 23.1625, 90)).isNull();
        assertThat(matcher.match(53.14, 23.155, 270)).isEqualTo(new BusLaneMatcher.Match(1, false));
        assertThat(matcher.match(53.14, 23.155, 90)).isEqualTo(new BusLaneMatcher.Match(1, true));
    }

    @Test
    void measuresHowMuchOfARouteRunsOnBusLanes() {
        // East along the lane and 670 m on past its end; then the same way back, against it.
        List<double[]> east = List.of(new double[] {53.13, 23.15}, new double[] {53.13, 23.17});
        BusLaneMatcher.Share share = matcher.share(east);
        assertThat(share.meters()).isCloseTo(1340, within(10.0));
        assertThat(share.laneMeters()).isCloseTo(670, within(25.0));
        assertThat(share.onLanes()).singleElement().satisfies(part -> {
            assertThat(part.get(0)).containsExactly(53.13, 23.15);
            assertThat(part.get(part.size() - 1)[1]).isCloseTo(23.16, within(0.0005));
        });
        BusLaneMatcher.Share west = matcher.share(List.of(new double[] {53.13, 23.17}, new double[] {53.13, 23.15}));
        assertThat(west.laneMeters()).isZero();
        assertThat(west.onLanes()).isEmpty();
        assertThat(matcher.share(List.of(new double[] {53.13, 23.15}, new double[] {53.13, 23.15})).meters()).isZero();
    }

    @Test
    void tellsSectionsWithBusLanesBothWays() {
        List<BusLane> lanes = List.of(LANES.get(0), LANES.get(1),
                // Lipowa's other carriageway, 18 m north, westbound; and a one-way lane alone.
                new BusLane(3, "Lipowa", "secondary", 0, 1, List.of(new double[] {53.130162, 23.15},
                        new double[] {53.130162, 23.16})),
                new BusLane(4, "Wiejska", "secondary", 1, 0, List.of(new double[] {53.11, 23.15},
                        new double[] {53.11, 23.16})));
        BusLaneMatcher both = new BusLaneMatcher(lanes, 15, 45);
        assertThat(both.bothWays(lanes)).containsExactly(true, true, true, false);
        assertThat(matcher.bothWays(LANES)).containsExactly(false, true);
    }

    @Test
    void groupsLanesByStreetAndDirection() {
        List<BusLane> lanes = List.of(LANES.get(0), LANES.get(1),
                new BusLane(3, "Lipowa", "secondary", 1, 0, List.of(new double[] {53.13, 23.16},
                        new double[] {53.1301, 23.17})),
                new BusLane(4, null, "service", 1, 0, List.of(new double[] {53.12, 23.15},
                        new double[] {53.121, 23.15})));
        BusLaneStretches stretches = new BusLaneStretches(lanes);

        assertThat(stretches.all()).extracting(s -> s.name() + " " + s.heading()).containsExactly(
                "Lipowa eastbound", "Sienkiewicza eastbound", "Sienkiewicza westbound", "unnamed road northbound");
        assertThat(stretches.all().get(0).ways()).containsExactly(1L, 3L);
        assertThat(stretches.all().get(0).meters()).isCloseTo(1340, within(10.0));
        // Against the way's direction: the points reversed.
        assertThat(stretches.all().get(2).paths().get(0).get(0)).containsExactly(53.14, 23.16);
        assertThat(stretches.of(new BusLaneMatcher.Match(1, false))).isEqualTo(2);
        assertThat(stretches.of(new BusLaneMatcher.Match(2, true))).isZero();
    }

    @Test
    void measuresDistancesAndHeadings() {
        assertThat(Geo.heading(new double[] {53.13, 23.15}, new double[] {53.14, 23.15})).isCloseTo(0, within(0.01));
        assertThat(Geo.heading(new double[] {53.13, 23.15}, new double[] {53.13, 23.14})).isCloseTo(270, within(0.01));
        assertThat(Geo.angle(350, 10)).isEqualTo(20);
        assertThat(Geo.angle(90, 270)).isEqualTo(180);
        assertThat(Geo.meters(new double[] {53.13, 23.15}, new double[] {53.14, 23.15})).isCloseTo(1113, within(1.0));
    }
}
