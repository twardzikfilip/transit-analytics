package com.transitanalytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class UserRoleDto {

    private UserRoleDto() {
    }

    public record Request(@NotBlank @Pattern(regexp = "^[A-Z_]{1,32}$") String name) {
    }

    public record Response(Long id, String name) {
    }
}
