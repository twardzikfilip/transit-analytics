package com.transitanalytics.routes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The way a bus probably drives between a depot and a stop: the shortest route along the streets from an OSRM server
 * (car profile), remembered per pair of points. Off, or when the server fails: a straight line, not remembered, so
 * the next look tries again.
 */
public class DepotRouter {

    private static final Logger log = LoggerFactory.getLogger(DepotRouter.class);

    /** A way between two points, [latitude, longitude] in order; {@code routed} false for a straight line. */
    public record Way(List<double[]> points, boolean routed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String code, List<Route> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(Geometry geometry) {
    }

    /** GeoJSON: [longitude, latitude] pairs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geometry(List<List<Double>> coordinates) {
    }

    private final MapProperties.Routing routing;
    private final RestClient client;
    private final JsonMapper json;
    private final Map<String, Way> cache = new ConcurrentHashMap<>();

    /** @param builder set up with the timeouts (see {@link MapConfig}) */
    public DepotRouter(MapProperties properties, RestClient.Builder builder, JsonMapper json) {
        this.routing = properties.routing();
        this.json = json;
        this.client = routing.enabled() && routing.url() != null ? builder.baseUrl(routing.url()).build() : null;
    }

    /** The way from one point to another, [latitude, longitude]. */
    public Way way(double[] from, double[] to) {
        if (client == null) {
            return straight(from, to);
        }
        String key = String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f", from[0], from[1], to[0], to[1]);
        Way known = cache.get(key);
        if (known != null) {
            return known;
        }
        try {
            byte[] body = client.get()
                    .uri(String.format(Locale.ROOT, "/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                            from[1], from[0], to[1], to[0]))
                    .retrieve().body(byte[].class);
            Response response = json.readValue(body, Response.class);
            if (!"Ok".equals(response.code()) || response.routes() == null || response.routes().isEmpty()) {
                return straight(from, to);
            }
            List<double[]> points = response.routes().get(0).geometry().coordinates().stream()
                    .map(c -> new double[] {c.get(1), c.get(0)}).toList();
            Way way = new Way(points, true);
            cache.put(key, way);
            return way;
        } catch (RuntimeException ex) {
            log.warn("No driving route from {} to {}: {}", from, to, ex.toString());
            return straight(from, to);
        }
    }

    private static Way straight(double[] from, double[] to) {
        return new Way(List.of(from, to), false);
    }
}
