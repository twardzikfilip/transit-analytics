package com.transitanalytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Full context test against a real PostgreSQL — run with INTEGRATION_TESTS=true and the DB up. */
@SpringBootTest
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
