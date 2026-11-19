package com.transitanalytics.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Places drawn on maps and how to route between them, from {@code map/depots.yml}. */
@ConfigurationProperties("app.map")
public record MapProperties(Routing routing, List<Depot> depots) {

    public MapProperties {
        routing = routing == null ? new Routing(false, null, null) : routing;
        depots = depots == null ? List.of() : List.copyOf(depots);
    }

    /** The depot with this code, or null. */
    public Depot depot(String code) {
        return depots.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    /**
     * Driving routes from an OSRM server; off (or failing): straight lines.
     *
     * @param url     the server, e.g. {@code https://router.project-osrm.org}
     * @param timeout per request; default 5 s
     */
    public record Routing(boolean enabled, String url, Duration timeout) {

        public Routing {
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }

    /**
     * A bus depot.
     *
     * @param code     the operating company whose depot it is (KPKM, KPK, KZK)
     * @param outlines its area, one or more closed rings of [latitude, longitude]
     * @param source   where the outline comes from
     */
    public record Depot(String code, String name, String address, String source, List<List<List<Double>>> outlines) {

        public Depot {
            outlines = outlines == null ? List.of() : List.copyOf(outlines);
        }

        /** The middle of its (first) outline, [latitude, longitude]: where routes to and from it start. */
        public double[] centre() {
            List<List<Double>> ring = outlines.get(0);
            int n = ring.size() > 1 && ring.get(0).equals(ring.get(ring.size() - 1)) ? ring.size() - 1 : ring.size();
            double lat = 0;
            double lon = 0;
            for (int i = 0; i < n; i++) {
                lat += ring.get(i).get(0);
                lon += ring.get(i).get(1);
            }
            return new double[] {lat / n, lon / n};
        }
    }
}
