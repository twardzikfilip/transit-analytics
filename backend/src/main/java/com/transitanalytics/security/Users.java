package com.transitanalytics.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The app's users (app_user, user_role). */
@Component
public class Users {

    /** @param role ADMIN or GUEST */
    public record Account(String username, String passwordHash, String role) {
    }

    private final JdbcTemplate jdbc;

    public Users(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> find(String username) {
        return jdbc.query("""
                SELECT u.username, u.password_hash, r.name AS role
                FROM app_user u JOIN user_role r ON r.id = u.role_id WHERE u.username = ?""",
                (rs, i) -> new Account(rs.getString("username"), rs.getString("password_hash"), rs.getString("role")),
                username).stream().findFirst();
    }

    public void setPasswordHash(String username, String hash) {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE username = ?", hash, username);
    }
}
