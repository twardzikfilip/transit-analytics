package com.transitanalytics.zbiorkom;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Białystok's vehicle positions from zbiorkom.live: polled, or read back from a saved response, with every vehicle
 * registered in the {@code vehicle} table.
 */
public class BialystokFeed {

    public static final String CITY = "bialystok";
    /** The city operator (Białostocka Komunikacja Miejska); zbiorkom reports it as "default". */
    public static final String CITY_AGENCY = "BKM";

    /** GTFS route types, named like the vehicle types used elsewhere in the app. */
    private static final Map<String, String> VEHICLE_TYPES = Map.of(
            "0", "TRAM", "1", "METRO", "2", "TRAIN", "3", "BUS", "11", "TROLLEYBUS");

    /**
     * One poll of the feed: the response as received, when it was fetched, and the parsed positions.
     *
     * @param raw response body, for archiving
     */
    public record Snapshot(byte[] raw, Instant fetchedAt, List<FeedPosition> positions) {
    }

    private final ZbiorkomClient client;
    private final ZbiorkomProperties properties;
    private final VehicleRegistry vehicles;
    private final Clock clock;

    public BialystokFeed(ZbiorkomClient client, ZbiorkomProperties properties, VehicleRegistry vehicles,
                         Clock clock) {
        this.client = client;
        this.properties = properties;
        this.vehicles = vehicles;
        this.clock = clock;
    }

    /** Polls the feed once. */
    public Snapshot poll() {
        Instant fetchedAt = clock.instant();
        byte[] raw = client.positionsRaw(CITY, properties.bialystok().bbox());
        return new Snapshot(raw, fetchedAt, positions(raw));
    }

    /** The response as received, without parsing it (so it can be archived before anything else can fail). */
    public byte[] fetchRaw() {
        return client.positionsRaw(CITY, properties.bialystok().bbox());
    }

    /** Positions from a response, e.g. one saved earlier; new vehicles are registered. Excluded lines are left out. */
    public List<FeedPosition> positions(byte[] response) {
        ZbiorkomFeed.Positions feed = client.parsePositions(response);
        List<ZbiorkomFeed.Position> raw = feed == null || feed.positions() == null ? List.of() : feed.positions()
                .stream()
                .filter(p -> p.vehicle() != null && p.vehicle().id() != null)
                .filter(p -> p.location() != null && p.location().length >= 2)
                .filter(p -> !properties.bialystok().excluded(p.routeName()))
                .toList();
        Map<String, Long> ids = vehicles.idsFor(raw.stream().map(p -> toVehicle(p.vehicle())).toList());
        return raw.stream().map(p -> toPosition(p, ids.get(p.vehicle().id()))).toList();
    }

    /**
     * Positions from a response without registering anything: vehicles have no id. For showing positions while the
     * database is down.
     */
    public List<FeedPosition> positionsWithoutIds(byte[] response) {
        ZbiorkomFeed.Positions feed = client.parsePositions(response);
        return feed == null || feed.positions() == null ? List.of() : feed.positions().stream()
                .filter(p -> p.vehicle() != null && p.vehicle().id() != null)
                .filter(p -> p.location() != null && p.location().length >= 2)
                .filter(p -> !properties.bialystok().excluded(p.routeName()))
                .map(p -> toPosition(p, null))
                .toList();
    }

    /**
     * The response without the excluded lines' positions, for the archive; as received when nothing is excluded or
     * it can't be read (it is kept whole rather than lost).
     */
    public byte[] withoutExcluded(byte[] response) {
        tools.jackson.databind.json.JsonMapper json = client.json();
        ZbiorkomProperties.Bialystok city = properties.bialystok();
        if (city.excludedLines() == null || city.excludedLines().isBlank() || response.length == 0) {
            return response;
        }
        try {
            tools.jackson.databind.JsonNode root = json.readTree(response);
            tools.jackson.databind.JsonNode positions = root.path("positions");
            if (!(root instanceof tools.jackson.databind.node.ObjectNode object)
                    || !(positions instanceof tools.jackson.databind.node.ArrayNode array)) {
                return response;
            }
            tools.jackson.databind.node.ArrayNode kept = json.createArrayNode();
            boolean dropped = false;
            for (tools.jackson.databind.JsonNode p : array) {
                if (city.excluded(p.path("routeName").asString(null))) {
                    dropped = true;
                } else {
                    kept.add(p);
                }
            }
            if (!dropped) {
                return response;
            }
            object.set("positions", kept);
            return json.writeValueAsBytes(object);
        } catch (RuntimeException ex) {
            return response;
        }
    }

    /** A saved response, {@code .json} or {@code .json.gz}. */
    public static byte[] read(Path file) {
        try (InputStream in = file.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(Files.newInputStream(file)) : Files.newInputStream(file)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + file, ex);
        }
    }

    static VehicleRegistry.Discovered toVehicle(ZbiorkomFeed.Vehicle v) {
        return new VehicleRegistry.Discovered(v.id(), vehicleType(v.type()), agency(v.agency()), v.model());
    }

    public static String vehicleType(String gtfsType) {
        return gtfsType == null ? "UNKNOWN" : VEHICLE_TYPES.getOrDefault(gtfsType, gtfsType);
    }

    public static String agency(String agency) {
        return agency == null || agency.equalsIgnoreCase("default") ? CITY_AGENCY : agency;
    }

    private static FeedPosition toPosition(ZbiorkomFeed.Position p, Long vehicleId) {
        VehicleRegistry.Discovered v = toVehicle(p.vehicle());
        List<FeedPosition.UpcomingStop> stops = p.upcomingStops() == null ? List.of() : p.upcomingStops().stream()
                .filter(Objects::nonNull)
                .map(s -> new FeedPosition.UpcomingStop(s.sequence(), s.name(), instant(s.scheduledArrival()),
                        instant(s.scheduledDeparture())))
                .toList();
        return new FeedPosition(vehicleId, v.vehicleNumber(), v.type(), v.agency(), v.model(), p.routeName(),
                p.routeColor(), p.brigade(), p.headsign(), p.location()[1], p.location()[0], p.bearing(),
                p.delay() == null ? null : Math.round(p.delay() / 1000.0), instant(p.timestamp()), stops);
    }

    private static Instant instant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}
