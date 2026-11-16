package com.transitanalytics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ShapePointDto {

    private ShapePointDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String shapeId,
            @NotNull @PositiveOrZero Integer sequence,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            Double shapeDistTraveled) {
    }

    public record Response(Long id, String shapeId, Integer sequence, Double latitude, Double longitude,
                           Double shapeDistTraveled) {
    }

    public record Filter(String shapeId) {
    }
}
