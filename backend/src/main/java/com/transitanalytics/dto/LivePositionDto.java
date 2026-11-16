package com.transitanalytics.dto;

import java.time.Instant;
import java.util.List;

/**
 * A vehicle's live position from an external feed. Not stored.
 *
 * @param vehicleId    id of the matching row in the vehicle table (null if it could not be registered yet)
 * @param delaySeconds delay against the timetable, negative when early
 * @param timestamp    when the feed recorded the position
 */
public record LivePositionDto(
        Long vehicleId,
        String vehicleNumber,
        String type,
        String agency,
        String model,
        String routeName,
        String routeColor,
        String brigade,
        String headsign,
        double latitude,
        double longitude,
        Double bearing,
        Long delaySeconds,
        Instant timestamp,
        List<UpcomingStop> upcomingStops) {

    public record UpcomingStop(Integer sequence, String name, Instant scheduledArrival, Instant scheduledDeparture) {
    }
}
