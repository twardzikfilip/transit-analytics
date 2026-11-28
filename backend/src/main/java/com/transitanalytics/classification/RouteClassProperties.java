package com.transitanalytics.classification;

import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.RouteCapacity;
import com.transitanalytics.domain.RouteGeometry;
import com.transitanalytics.domain.RouteSubtype;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * How routes are classified, from {@code classification/routes.yml} (the rules are explained there).
 *
 * @param agencies    the operators whose routes are classified (agency names as in the timetable)
 * @param refreshCron when routes are classified again (Warsaw time)
 */
@ConfigurationProperties("app.route-classes")
public record RouteClassProperties(List<String> agencies, String refreshCron, Geometry geometry, Capacity capacity) {

    public RouteClassProperties {
        agencies = agencies == null ? List.of() : List.copyOf(agencies);
    }

    /**
     * @param centre          the city centre, [latitude, longitude] points in order; empty: Osiedle Centrum
     *                        ({@link com.transitanalytics.domain.CityBoundaries#CENTRE})
     * @param centralTermini  places outside the centre that count as central for a route ending there
     */
    public record Geometry(List<List<Double>> centre, double centreMarginMeters, double terminusMarginMeters,
                           List<CentralTerminus> centralTermini, double centralTerminusMarginMeters,
                           double diameterMinAngle, double arcantialMinAngle, double tangentialMinAngle,
                           Map<String, RouteGeometry> overrides) {

        public Geometry {
            centre = centre == null ? List.of() : List.copyOf(centre);
            centralTermini = centralTermini == null ? List.of() : List.copyOf(centralTermini);
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        }

        /** The centre as a closed ring of {latitude, longitude}; the default when none is set. */
        public double[][] centreRing(double[][] fallback) {
            if (centre.isEmpty()) {
                return fallback;
            }
            List<double[]> ring = new java.util.ArrayList<>(centre.stream()
                    .map(p -> new double[] {p.get(0), p.get(1)}).toList());
            if (!java.util.Arrays.equals(ring.get(0), ring.get(ring.size() - 1))) {
                ring.add(ring.get(0));
            }
            return ring.toArray(double[][]::new);
        }
    }

    /** A central interchange outside the centre (a route ending there is radial), as [latitude, longitude]. */
    public record CentralTerminus(String name, double latitude, double longitude) {
    }

    /**
     * @param skipSubtypes kinds of route given no capacity class (night routes)
     * @param peak         peak windows, "HH:MM-HH:MM"; by default the app's ({@link CityTime#PEAK})
     * @param rules        tried in order; the first whose conditions all hold gives the class
     */
    public record Capacity(List<RouteSubtype> skipSubtypes, int lookbackDays, double megaEveryDayShare,
                           double megaSomeShare, List<String> peak, List<Rule> rules, RouteCapacity fallback,
                           Map<String, RouteCapacity> overrides) {

        public Capacity {
            skipSubtypes = skipSubtypes == null ? List.of() : List.copyOf(skipSubtypes);
            peak = peak == null || peak.isEmpty() ? CityTime.PEAK : List.copyOf(peak);
            rules = rules == null ? List.of() : List.copyOf(rules);
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        }
    }

    /** How often a route runs MEGA buses. */
    public enum Mega {
        /** On every day type it runs, at least the every-day share. */
        EVERY_DAY,
        /** On weekdays at least the "some" share (every-day routes too). */
        SOME,
        /** Less than that. */
        NONE,
        /** No condition. */
        ANY
    }

    /** A capacity class and its conditions; a condition left out always holds. */
    public record Rule(RouteCapacity capacityClass, Mega mega, Double peakHeadwayUnder, Double averageHeadwayUnder,
                       Integer minTripsPerDirection, Double maxHeadwayCv) {
    }
}
