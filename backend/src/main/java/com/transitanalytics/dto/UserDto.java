package com.transitanalytics.dto;

import java.time.Instant;

/** The app's users, as the Users page lists and adds them (admins only). */
public final class UserDto {

    private UserDto() {
    }

    /** @param role ADMIN or GUEST */
    public record User(String username, String role, Instant createdAt) {
    }

    /** A user to add; the rules are in {@code UserRules}. */
    public record NewUser(String username, String password, String role) {
    }
}
