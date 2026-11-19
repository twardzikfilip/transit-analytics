package com.transitanalytics.zbiorkom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Raw payloads of the zbiorkom.live API, as received. Unknown fields are ignored. */
public final class ZbiorkomFeed {

    private ZbiorkomFeed() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Positions(List<Position> positions) {
    }

    /**
     * @param location  [longitude, latitude]
     * @param timestamp epoch milliseconds
     * @param delay     milliseconds, negative when early
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Position(Vehicle vehicle, String routeName, String routeColor, String brigade, double[] location,
                           Long timestamp, Double bearing, Long delay, String headsign, List<Stop> upcomingStops) {
    }

    /** @param type GTFS route type code, e.g. "3" for bus */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String id, String type, String agency, String model) {
    }

    /** Times in epoch milliseconds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(Integer sequence, String name, Long scheduledArrival, Long scheduledDeparture) {
    }
}
