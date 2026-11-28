package com.transitanalytics.classification;

import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.domain.RouteGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteGeometryClassifierTest {

    /** A square centre of about 1.1 × 0.67 km around 53.13 N, 23.16 E. */
    private static final double[][] CENTRE = {
            {53.125, 23.15}, {53.125, 23.17}, {53.135, 23.17}, {53.135, 23.15}, {53.125, 23.15}};
    private static final RouteClassProperties.Geometry RULES =
            new RouteClassProperties.Geometry(List.of(), 200, 400,
                    List.of(new RouteClassProperties.CentralTerminus("Station", 53.13, 23.13)), 150, 160, 30, 60, Map.of());

    private final RouteGeometryClassifier classifier = new RouteGeometryClassifier(RULES, CENTRE);

    private static List<double[]> route(double[]... points) {
        return List.of(points);
    }

    @Test
    void classesRoutesByHowTheyRunRelativeToTheCentre() {
        double[] middle = {53.13, 23.16};
        double[] north = {53.17, 23.16};
        double[] south = {53.09, 23.16};
        double[] east = {53.13, 23.22};
        double[] northEast = {53.16, 23.20};
        double[] west = {53.13, 23.10};
        // From the centre out north.
        assertThat(classifier.classify(route(middle, north))).isEqualTo(RouteGeometry.RADIAL);
        // North to south through the centre.
        assertThat(classifier.classify(route(north, middle, south))).isEqualTo(RouteGeometry.DIAMETER);
        // North to east through the centre: 90° apart.
        assertThat(classifier.classify(route(north, middle, east))).isEqualTo(RouteGeometry.ARCANTIAL);
        // Out of the centre and back on the same side: a loop, radial.
        assertThat(classifier.classify(route(north, middle, new double[] {53.17, 23.17})))
                .isEqualTo(RouteGeometry.RADIAL);
        // West to north-east round the centre, never in it.
        assertThat(classifier.classify(route(west, new double[] {53.16, 23.12}, new double[] {53.17, 23.16},
                northEast))).isEqualTo(RouteGeometry.TANGENTIAL);
        // From far north to 2 km north of the centre: short of it.
        assertThat(classifier.classify(route(new double[] {53.20, 23.16}, new double[] {53.155, 23.16})))
                .isEqualTo(RouteGeometry.FEEDER);
        // From the station (2 km west of the centre) through the centre out east: radial, the station is central.
        double[] station = {53.13, 23.13};
        assertThat(classifier.classify(route(station, middle, east))).isEqualTo(RouteGeometry.RADIAL);
        // From 300 m off the station: not at it, so through the centre at 180°, a diameter.
        assertThat(classifier.classify(route(new double[] {53.13, 23.1255}, middle, east)))
                .isEqualTo(RouteGeometry.DIAMETER);
        // South-west up past the west of the centre, north-west and back west: the ends 22° apart, but it swings
        // 100° round the centre, tangential (like route 23).
        double[] southWest = {53.10, 23.12};
        assertThat(classifier.classify(route(southWest, west, new double[] {53.16, 23.12}, new double[] {53.11, 23.10})))
                .isEqualTo(RouteGeometry.TANGENTIAL);
        assertThat(classifier.classify(route(north))).isNull();
        assertThat(classifier.classify(null)).isNull();
    }

    @Test
    void measuresDistancesToTheCentreAndTheAngleBetweenTheEnds() {
        RouteGeometryClassifier.Measures m = classifier.measure(route(new double[] {53.13, 23.16},
                new double[] {53.145, 23.16}));
        assertThat(m.firstEndToCentre()).isZero();
        assertThat(m.lastEndToCentre()).isCloseTo(1113, within(5.0));
        assertThat(m.closestToCentre()).isZero();
        RouteGeometryClassifier.Measures back = classifier.measure(route(new double[] {53.10, 23.16},
                new double[] {53.13, 23.10}, new double[] {53.11, 23.12}));
        assertThat(back.sweep()).isCloseTo(90, within(1.0));
        assertThat(back.angleBetweenEnds()).isLessThan(60);
    }

    @Test
    void worksWithTheConfiguredCityCentre() {
        var rules = ClassificationConfigs.routes().geometry();
        var real = new RouteGeometryClassifier(rules, rules.centreRing(CityBoundaries.CENTRE));
        double[] rynek = {53.1322, 23.1590};
        double[] wysokiStoczek = {53.155, 23.125};
        double[] stRoch = {53.13395, 23.14907};
        double[] poleska = {53.14202, 23.15931};
        double[] monteCassino = {53.13315, 23.13838};
        // From Rynek Kościuszki out north-west: radial.
        assertThat(real.classify(route(rynek, wysokiStoczek))).isEqualTo(RouteGeometry.RADIAL);
        // Round the centre by Monte Cassino, św. Rocha and Poleska, not into it: tangential.
        assertThat(real.classify(route(new double[] {53.115, 23.135}, monteCassino, stRoch, poleska,
                new double[] {53.150, 23.175}))).isEqualTo(RouteGeometry.TANGENTIAL);
        // The same, on into Lipowa and Rynek Kościuszki and out east: through the centre.
        assertThat(real.classify(route(new double[] {53.115, 23.135}, monteCassino, stRoch, rynek,
                new double[] {53.135, 23.20}))).isEqualTo(RouteGeometry.ARCANTIAL);
        assertThat(rules.centreRing(CityBoundaries.CENTRE).length).isEqualTo(9);
    }

    @Test
    void fallsBackToOsiedleCentrumWithoutACentreSet() {
        var none = new RouteClassProperties.Geometry(null, 200, 400, null, 150, 160, 30, 60, null);
        assertThat(none.centreRing(CityBoundaries.CENTRE)).isSameAs(CityBoundaries.CENTRE);
        var closed = new RouteClassProperties.Geometry(List.of(List.of(1.0, 1.0), List.of(1.0, 2.0),
                List.of(2.0, 2.0), List.of(1.0, 1.0)), 200, 400, null, 150, 160, 30, 60, null);
        assertThat(closed.centreRing(CityBoundaries.CENTRE).length).isEqualTo(4);
    }
}
