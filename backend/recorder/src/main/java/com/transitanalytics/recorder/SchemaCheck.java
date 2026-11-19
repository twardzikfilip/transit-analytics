package com.transitanalytics.recorder;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tables the recorder writes to must exist: the backend creates them (Flyway). Checked at startup, so a missing
 * schema shows as a clear message rather than a failed poll every 10 seconds. A database that is down at startup is
 * no reason to stop: polls are spooled until it is back.
 */
@Component
@Order(0)
public class SchemaCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String missing;
        try {
            missing = jdbc.queryForObject("""
                    SELECT string_agg(t, ', ') FROM unnest(ARRAY['vehicle', 'position_sample']) AS t
                    WHERE to_regclass(t) IS NULL""", String.class);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(SchemaCheck.class)
                    .warn("Database not reachable at startup; polls are kept until it is: {}", ex.getMessage());
            return;
        }
        if (missing != null) {
            throw new IllegalStateException("Tables missing: " + missing
                    + ". The backend creates the schema: start it once against this database first.");
        }
    }
}
