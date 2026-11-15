package com.transitanalytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Full context test against a real PostgreSQL — run with INTEGRATION_TESTS=true and the DB up. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
