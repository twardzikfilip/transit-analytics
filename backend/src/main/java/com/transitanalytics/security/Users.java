package com.transitanalytics.security;

import com.transitanalytics.dto.UserDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /** Every user, by name. */
    public List<UserDto.User> list() {
        return jdbc.query("""
                SELECT u.username, r.name AS role, u.created_at
                FROM app_user u JOIN user_role r ON r.id = u.role_id ORDER BY lower(u.username)""",
                (rs, i) -> new UserDto.User(rs.getString("username"), rs.getString("role"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** Whether a user has this name, in any case ("Ann" and "ann" are one name). */
    public boolean exists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(username) = lower(?))", Boolean.class, username));
    }

    /** Adds a user with a role (ADMIN or GUEST) and a password hash. */
    public UserDto.User create(String username, String passwordHash, String role) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (username, password_hash, role_id)
                SELECT ?, ?, id FROM user_role WHERE name = ?
                RETURNING username, created_at""",
                (rs, i) -> new UserDto.User(rs.getString("username"), role, rs.getTimestamp("created_at").toInstant()),
                username, passwordHash, role);
    }

    /** Removes a user; whether there was one. */
    public boolean delete(String username) {
        return jdbc.update("DELETE FROM app_user WHERE username = ?", username) > 0;
    }

    public void setPasswordHash(String username, String hash) {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE username = ?", hash, username);
    }
}
