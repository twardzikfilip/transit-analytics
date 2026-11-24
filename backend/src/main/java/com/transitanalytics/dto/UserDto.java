package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class UserDto {

    private UserDto() {
    }

    /** {@code password} is required on create and optional on update (blank keeps the current one). */
    public record Request(
            @NotBlank @Size(min = 3, max = 64) String username,
            @Size(min = 10, max = 72) String password,
            @NotBlank String role) {
    }

    public record Response(Long id, String username, String role, Instant createdAt) {
    }
}
