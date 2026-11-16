package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AgencyDto {

    private AgencyDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String gtfsId,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 512) String url,
            @NotBlank @Size(max = 64) String timezone,
            @Size(max = 16) String lang,
            @Size(max = 64) String phone,
            @Size(max = 512) String fareUrl,
            @Size(max = 255) String email) {
    }

    public record Response(Long id, String gtfsId, String name, String url, String timezone, String lang,
                           String phone, String fareUrl, String email) {
    }
}
