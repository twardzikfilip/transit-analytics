package com.transitanalytics.recorder;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the passwords of the database server's roles while the test database is migrated. Roles belong to the whole
 * server, so the migrations that set the recorder's and Grafana's passwords would set them for the live database too,
 * to what the test run was given. Each password is put back in the migration's own transaction, so it never changes
 * for anyone else. The same as the backend tests' class of this name, which is not on the classpath here.
 */
class SharedRoles implements Callback {

    private final Map<String, String> passwords = new HashMap<>();

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                passwords.clear();
                passwords.putAll(read(context.getConnection()));
            } else {
                restore(context.getConnection());
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("The server's role passwords could not be kept", ex);
        }
    }

    /** Role passwords as the server keeps them (hashed; null: none). */
    private static Map<String, String> read(Connection connection) throws SQLException {
        Map<String, String> found = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT rolname, rolpassword FROM pg_authid")) {
            while (rs.next()) {
                found.put(rs.getString(1), rs.getString(2));
            }
        }
        return found;
    }

    private void restore(Connection connection) throws SQLException {
        for (Map.Entry<String, String> now : read(connection).entrySet()) {
            String role = now.getKey();
            String before = passwords.get(role);
            if (!passwords.containsKey(role) || java.util.Objects.equals(before, now.getValue())) {
                continue;
            }
            String sql;
            try (PreparedStatement statement = connection.prepareStatement("SELECT format('ALTER ROLE %I PASSWORD ', ?)"
                    + " || coalesce(quote_literal(?::text), 'NULL')")) {
                statement.setString(1, role);
                statement.setString(2, before);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    sql = rs.getString(1);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    @Override
    public String getCallbackName() {
        return "shared-roles";
    }
}
