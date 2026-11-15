package com.transitanalytics.dto;

import jakarta.validation.constraints.*;

public final class StopDto {

    private StopDto() {
    }

    public record Request(
            @Size(max = 32) String code,
            @NotBlank @Size(max = 255) String name,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {
    }

    public record Response(Long id, String code, String name, Double latitude, Double longitude) {
    }
}
