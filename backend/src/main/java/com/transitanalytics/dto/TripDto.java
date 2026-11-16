package com.transitanalytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TripDto {

    private TripDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String gtfsId,
            @NotNull Long routeId,
            @NotBlank @Size(max = 64) String serviceId,
            @Size(max = 255) String headsign,
            @Size(max = 64) String shortName,
            @Min(0) @Max(1) Short direction,
            @Size(max = 64) String blockId,
            @Size(max = 64) String shapeId,
            @Min(0) @Max(2) Short wheelchairAccessible,
            @Min(0) @Max(2) Short bikesAllowed) {
    }

    public record Response(Long id, String gtfsId, Long routeId, String routeName, String routeColor,
                           String serviceId, String headsign, String shortName, Short direction, String blockId,
                           String shapeId, Short wheelchairAccessible, Short bikesAllowed) {
    }

    /** @param q text in the headsign */
    public record Filter(Long routeId, String serviceId, Short direction, String q) {
    }
}
