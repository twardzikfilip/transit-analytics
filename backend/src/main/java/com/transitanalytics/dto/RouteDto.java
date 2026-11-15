package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RouteDto {

    private RouteDto() {
    }

    public record Request(
            @NotBlank @Size(max = 32) String shortName,
            @Size(max = 255) String longName,
            @NotBlank @Size(max = 32) String type,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color) {
    }

    public record Response(Long id, String shortName, String longName, String type, String color) {
    }
}
