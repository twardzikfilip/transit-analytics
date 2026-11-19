package com.transitanalytics.buslanes;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Where bus lanes come from and how buses are matched to them, from {@code map/bus-lanes.yml} (explained there).
 *
 * @param file          where refreshed lanes are saved, and read from when present
 * @param refreshCron   when lanes are fetched again; "-" never
 * @param overpass      Overpass servers, tried in order
 * @param areaRelation  the OpenStreetMap relation of the city limits
 * @param matchMeters   how far from a lane a bus may be and still be on it
 * @param matchAngle    how far (degrees) a bus's heading may be from the lane's direction
 * @param official      the length the city gives, for comparison; may be null
 */
@ConfigurationProperties("app.bus-lanes")
public record BusLaneProperties(String file, String refreshCron, List<String> overpass, Duration timeout,
                                long areaRelation, double matchMeters, double matchAngle, Official official) {

    public BusLaneProperties {
        overpass = overpass == null ? List.of() : List.copyOf(overpass);
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        matchMeters = matchMeters <= 0 ? 15 : matchMeters;
        matchAngle = matchAngle <= 0 ? 45 : matchAngle;
    }

    /** An official figure for the bus lanes' length. */
    public record Official(double km, String date, String source, String url) {
    }
}
