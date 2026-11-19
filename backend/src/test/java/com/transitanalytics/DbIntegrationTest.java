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
        "app.map.routing.enabled=false",
        "app.bus-lanes.refresh-cron=-",
        "app.bus-lanes.file="
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
public @interface DbIntegrationTest {
}
