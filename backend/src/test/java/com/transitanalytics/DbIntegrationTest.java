package com.transitanalytics;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test against the real database with the full application context, plus a position writer to record test data:
 * telemetry export, the startup warm-ups and street routing (an outside service) are off. Runs only with INTEGRATION_TESTS=true (and the database up). Add {@code @Transactional} to roll test data back.
 * <p>
 * It uses its own database, {@code transit_test} on the same server ({@code TEST_DB_URL} to change it), never the one
 * the recorder writes to: what a test leaves behind can't mix with the recordings.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(TestPositionWriter.class)
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false",
        "app.warm-up.enabled=false",
        "app.day-summaries.enabled=false",
        "app.map.routing.enabled=false",
        "app.bus-lanes.refresh-cron=-",
        "app.bus-lanes.file=",
        "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/transit_test}"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
public @interface DbIntegrationTest {
}
