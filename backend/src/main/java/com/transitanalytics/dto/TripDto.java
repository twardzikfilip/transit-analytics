package com.transitanalytics.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class TripDto {

    private TripDto() {
    }

    public record Request(
            @NotNull Long routeId,
            Long vehicleId,
            @Size(max = 255) String headsign,
            Short direction,
            Instant startTime,
            Instant endTime) {
    }

    public record Response(Long id, Long routeId, Long vehicleId, String headsign, Short direction,
                           Instant startTime, Instant endTime) {
    }
}
