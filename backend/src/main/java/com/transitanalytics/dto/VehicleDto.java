package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class VehicleDto {

    private VehicleDto() {
    }

    public record Request(
            @NotBlank @Size(max = 32) String vehicleNumber,
            @NotBlank @Size(max = 32) String type,
            @PositiveOrZero Integer capacity,
            Boolean active) {
    }

    public record Response(Long id, String vehicleNumber, String type, Integer capacity, boolean active) {
    }
}
