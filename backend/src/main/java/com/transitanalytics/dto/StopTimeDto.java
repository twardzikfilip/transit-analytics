package com.transitanalytics.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class StopTimeDto {

    private StopTimeDto() {
    }

    /** GTFS time: hours may exceed 24 for trips that run past midnight. */
    private static final String TIME = "^\\d{2}:[0-5]\\d:[0-5]\\d$";

    public record Request(
            @NotNull Long tripId,
            @NotNull Long stopId,
            @NotNull @PositiveOrZero Integer stopSequence,
            @Pattern(regexp = TIME) String arrivalTime,
            @Pattern(regexp = TIME) String departureTime,
            @Size(max = 255) String stopHeadsign,
            Short pickupType,
            Short dropOffType,
            Double shapeDistTraveled,
            Short timepoint) {
    }

    /** @param serviceId the trip's service (which days it runs), see calendar dates */
    public record Response(Long id, Long tripId, String tripGtfsId, String tripHeadsign, String serviceId, Long routeId,
                           String routeName, String routeColor, Long stopId, String stopCode, String stopName,
                           Integer stopSequence, String arrivalTime, String departureTime, String stopHeadsign,
                           Short pickupType, Short dropOffType, Double shapeDistTraveled, Short timepoint) {
    }

    public record Filter(Long tripId, Long stopId) {
    }
}
