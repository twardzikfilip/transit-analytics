package com.transitanalytics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class StopDto {

    private StopDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String gtfsId,
            @Size(max = 32) String code,
            @NotBlank @Size(max = 255) String name,
            String description,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max = 64) String zoneId,
            @Size(max = 512) String url,
            Short locationType,
            @Size(max = 64) String parentStation,
            Short wheelchairBoarding,
            @Size(max = 32) String platformCode) {
    }

    public record Response(Long id, String feed, String gtfsId, String code, String name, String description, Double latitude,
                           Double longitude, String zoneId, String url, Short locationType, String parentStation,
                           Short wheelchairBoarding, String platformCode) {
    }

    /** @param q text in the name or code */
    public record Filter(String q, String zoneId) {
    }
}
