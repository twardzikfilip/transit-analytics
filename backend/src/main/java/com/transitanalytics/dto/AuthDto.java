package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;

public final class AuthDto {

    private AuthDto() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String username, String role) {
    }

    public record Me(String username, String role) {
    }
}
