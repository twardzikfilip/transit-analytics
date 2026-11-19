package com.transitanalytics.buslanes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;

/**
 * The city's bus lanes: the saved copy ({@code file}) when there is one, else the built-in
 * {@code map/bus-lanes.json}; fetched again from OpenStreetMap (Overpass) at {@code refresh-cron} or on request.
 */
public class BusLanes {

    private static final Logger log = LoggerFactory.getLogger(BusLanes.class);
    static final String BUILT_IN = "map/bus-lanes.json";

    /** The lanes with what is built from them; replaced as a whole on a refresh. */
    /** @param bothWays per lane: buses have a lane the other way too (see {@link BusLaneMatcher#bothWays}) */
    public record Snapshot(BusLaneData data, BusLaneMatcher matcher, BusLaneStretches stretches, boolean[] bothWays,
                           int version) {
    }

    private final BusLaneProperties properties;
    private final RestClient client;
    private final JsonMapper json;
    private final Clock clock;
    private volatile Snapshot current;

    public BusLanes(BusLaneProperties properties, RestClient client, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.json = json;
        this.clock = clock;
        this.current = snapshot(load(), 1);
        log.info("Bus lanes: {} sections, {} km of lane ({})", current.data().lanes().size(),
                String.format(java.util.Locale.ROOT, "%.1f", current.data().laneKm()), current.data().source());
    }

    public Snapshot current() {
        return current;
    }

    public BusLaneProperties properties() {
        return properties;
    }

    private Snapshot snapshot(BusLaneData data, int version) {
        BusLaneMatcher matcher = new BusLaneMatcher(data.lanes(), properties.matchMeters(), properties.matchAngle());
        return new Snapshot(data, matcher, new BusLaneStretches(data.lanes()), matcher.bothWays(data.lanes()), version);
    }

    private BusLaneData load() {
        if (properties.file() != null && !properties.file().isBlank() && Files.isRegularFile(Path.of(properties.file()))) {
            try (InputStream in = Files.newInputStream(Path.of(properties.file()))) {
                return json.readValue(in, BusLaneData.class);
            } catch (IOException | RuntimeException ex) {
                log.warn("Bus lanes in {} unreadable, using the built-in ones: {}", properties.file(), ex.toString());
            }
        }
        try (InputStream in = new ClassPathResource(BUILT_IN).getInputStream()) {
            return json.readValue(in, BusLaneData.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Fetches on schedule; a failure is logged and the lanes stay as they are. */
    @Scheduled(cron = "${app.bus-lanes.refresh-cron:-}", zone = "Europe/Warsaw")
    void refreshOnSchedule() {
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.warn("Bus lanes not refreshed: {}", ex.toString());
        }
    }

    /**
     * Fetches the lanes from the first Overpass server that answers, saves them to {@code file} and uses them.
     *
     * @throws IllegalStateException when no server answers or the answer has no lanes (the lanes stay as they are)
     */
    public synchronized Snapshot refresh() {
        String query = OverpassBusLanes.query(properties.areaRelation());
        RuntimeException last = null;
        for (String url : properties.overpass()) {
            try {
                byte[] body = client.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                        .retrieve().body(byte[].class);
                List<BusLane> lanes = OverpassBusLanes.parse(json.readValue(body, JsonNode.class));
                if (lanes.isEmpty()) {
                    throw new IllegalStateException("no bus lanes in the answer");
                }
                BusLaneData data = new BusLaneData("OpenStreetMap via " + java.net.URI.create(url).getHost()
                        + " (© OpenStreetMap contributors, ODbL)", clock.instant(), lanes);
                save(data);
                current = snapshot(data, current.version() + 1);
                log.info("Bus lanes refreshed from {}: {} sections, {} km of lane", url, lanes.size(),
                        String.format(java.util.Locale.ROOT, "%.1f", data.laneKm()));
                return current;
            } catch (RuntimeException ex) {
                log.warn("Bus lanes: {} failed: {}", url, ex.toString());
                last = ex;
            }
        }
        throw new IllegalStateException("No Overpass server gave the bus lanes", last);
    }

    private void save(BusLaneData data) {
        if (properties.file() == null || properties.file().isBlank()) {
            return;
        }
        try {
            Path file = Path.of(properties.file()).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "bus-lanes", ".json");
            Files.write(tmp, json.writeValueAsBytes(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            log.warn("Bus lanes not saved to {}: {}", properties.file(), ex.toString());
        }
    }
}
