package com.transitanalytics.zbiorkom;

import java.time.Instant;
import java.util.List;

/**
 * A vehicle's position from the zbiorkom.live feed, as recorded: the backend's live DTO without the line's kind,
 * which only the backend knows.
 *
 * @param vehicleId    the vehicle's id in the {@code vehicle} table; null when it couldn't be registered
 * @param delaySeconds against the timetable; negative when early
 * @param timestamp    when the vehicle reported this position
 */
public record FeedPosition(Long vehicleId, String vehicleNumber, String type, String agency, String model,
                           String routeName, String routeColor, String brigade, String headsign, double latitude,
                           double longitude, Double bearing, Long delaySeconds, Instant timestamp,
                           List<UpcomingStop> upcomingStops) {

    public record UpcomingStop(Integer sequence, String name, Instant scheduledArrival, Instant scheduledDeparture) {
    }
}
