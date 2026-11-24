package com.transitanalytics;

import com.transitanalytics.recording.PositionSampleWriter;
import org.flywaydb.core.api.callback.Callback;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** The position writer transit-recorder uses, for tests to record positions with. The backend itself doesn't write any. */
@TestConfiguration
public class TestPositionWriter {

    /** Migrating the test database must not change the server's roles. */
    @Bean
    Callback sharedRoles() {
        return new SharedRoles();
    }

    @Bean
    PositionSampleWriter positionSampleWriter(JdbcTemplate jdbc) {
        return new PositionSampleWriter(jdbc);
    }
}
