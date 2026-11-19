package com.transitanalytics.recorder;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test against the real database with the recorder's application context. The schema is created the way the
 * backend does it, with its Flyway migrations (only in these tests: the recorder never migrates), logged in as the
 * owner; polling and telemetry export are off. Runs only with INTEGRATION_TESTS=true (and the database up).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.username=${DB_OWNER:transit}",
        "spring.datasource.password=${DB_OWNER_PASSWORD:transit}",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:migration",
        "spring.flyway.placeholders.grafana_db_password=grafana_reader",
        "spring.flyway.placeholders.recorder_db_password=transit_recorder",
        "app.recording.enabled=false",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
public @interface RecorderDbTest {
}
