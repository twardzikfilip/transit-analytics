package com.transitanalytics.common;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Days stored before transit lines were named routes read as new ones. */
class StoredRowsUpgradeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void namesLinesRoutesButLeavesTimelines() {
        JsonNode old = JSON.readTree("""
                [{"line": "103", "lines": ["1"], "replacementLine": "9", "timeline": [], "trip": {"line": "5"}}]""");

        JsonNode now = DaySummaries.withRouteNames(old);

        assertThat(now.get(0).propertyNames()).containsExactly("route", "routes", "replacementRoute", "timeline", "trip");
        assertThat(now.get(0).get("trip").get("route").asString()).isEqualTo("5");
    }
}
