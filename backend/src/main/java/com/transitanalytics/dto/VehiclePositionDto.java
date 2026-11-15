package com.transitanalytics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class VehiclePositionDto {

    private VehiclePositionDto() {
    }

    public record Request(
            @NotNull Long vehicleId,
            Long tripId,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            Double bearing,
            Double speed,
            Instant recordedAt) {
    }

    public record Response(Long id, Long vehicleId, String vehicleNumber, Long tripId, Double latitude,
                           Double longitude, Double bearing, Double speed, Instant recordedAt) {
    }
}
