package com.transitanalytics.zbiorkom;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.LineSubtypes;
import com.transitanalytics.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live data for Białystok from zbiorkom.live. Fetched on demand and not stored, except that vehicles seen for the
 * first time are registered in the vehicle table.
 */
@Service
public class ZbiorkomBialystokService {

    static final String CITY = "bialystok";

    private static final Logger log = LoggerFactory.getLogger(ZbiorkomBialystokService.class);

    /** The city operator (Białostocka Komunikacja Miejska); zbiorkom reports it as "default". */
    public static final String CITY_AGENCY = "BKM";
    private static final String DEFAULT_AGENCY = CITY_AGENCY;

    /** GTFS route types, named like the vehicle types used elsewhere in the app. */
    private static final Map<String, String> VEHICLE_TYPES = Map.of(
            "0", "TRAM", "1", "METRO", "2", "TRAIN", "3", "BUS", "11", "TROLLEYBUS");

    private final ZbiorkomClient client;
    private final ZbiorkomProperties properties;
    private final VehicleService vehicleService;
    private final LineSubtypes lineSubtypes;

    public ZbiorkomBialystokService(ZbiorkomClient client, ZbiorkomProperties properties,
                                    VehicleService vehicleService, LineSubtypes lineSubtypes) {
        this.client = client;
        this.properties = properties;
        this.vehicleService = vehicleService;
        this.lineSubtypes = lineSubtypes;
    }

    /**
     * One poll of the feed: the response as received, when it was fetched, and the parsed positions.
     *
     * @param raw       response body, for archiving
     * @param fetchedAt when the request was made
     */
    public record Snapshot(byte[] raw, Instant fetchedAt, List<LivePositionDto> positions) {
    }

    /** Current position of every vehicle in the Białystok area. */
    public List<LivePositionDto> positions() {
        return snapshot().positions();
    }

    /** Polls the feed once; vehicles seen for the first time are registered. */
    public Snapshot snapshot() {
        Instant fetchedAt = Instant.now();
        byte[] raw = client.positionsRaw(CITY, properties.bialystok().bbox());
        return new Snapshot(raw, fetchedAt, toPositions(client.parsePositions(raw)));
    }

    /** Positions from a response saved earlier (e.g. a file being imported); new vehicles are registered. */
    public List<LivePositionDto> positionsFrom(byte[] savedResponse) {
        return toPositions(client.parsePositions(savedResponse));
    }

    private List<LivePositionDto> toPositions(ZbiorkomFeed.Positions feed) {
        List<ZbiorkomFeed.Position> raw = feed == null || feed.positions() == null ? List.of() : feed.positions()
                .stream()
                .filter(p -> p.vehicle() != null && p.vehicle().id() != null)
                .filter(p -> p.location() != null && p.location().length >= 2)
                .toList();

        Map<String, Long> vehicleIds = registerVehicles(raw.stream().map(p -> toVehicle(p.vehicle())).toList());
        return raw.stream().map(p -> toDto(p, vehicleIds.get(p.vehicle().id()))).toList();
    }

    private Map<String, Long> registerVehicles(List<VehicleDto.Discovered> vehicles) {
        try {
            return vehicleService.registerMissing(vehicles);
        } catch (DataIntegrityViolationException ex) {
            // Another request registered the same vehicle at the same moment; the next poll picks up its id.
            log.warn("Concurrent vehicle registration, returning positions without vehicle ids: {}",
                    ex.getMostSpecificCause().getMessage());
            return Map.of();
        }
    }

    static VehicleDto.Discovered toVehicle(ZbiorkomFeed.Vehicle v) {
        return new VehicleDto.Discovered(v.id(), vehicleType(v.type()), agency(v.agency()), v.model());
    }

    static String vehicleType(String gtfsType) {
        return gtfsType == null ? "UNKNOWN" : VEHICLE_TYPES.getOrDefault(gtfsType, gtfsType);
    }

    static String agency(String agency) {
        return agency == null || agency.equalsIgnoreCase("default") ? DEFAULT_AGENCY : agency;
    }

    private LivePositionDto toDto(ZbiorkomFeed.Position p, Long vehicleId) {
        VehicleDto.Discovered v = toVehicle(p.vehicle());
        List<LivePositionDto.UpcomingStop> stops = p.upcomingStops() == null ? List.of() : p.upcomingStops().stream()
                .filter(Objects::nonNull)
                .map(s -> new LivePositionDto.UpcomingStop(s.sequence(), s.name(),
                        instant(s.scheduledArrival()), instant(s.scheduledDeparture())))
                .toList();
        return new LivePositionDto(vehicleId, v.vehicleNumber(), v.type(), v.agency(), v.model(),
                p.routeName(), p.routeColor(), p.brigade(), p.headsign(),
                p.location()[1], p.location()[0], p.bearing(),
                p.delay() == null ? null : Math.round(p.delay() / 1000.0),
                instant(p.timestamp()), stops, lineSubtypes.of(p.routeName()));
    }

    private static Instant instant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}
