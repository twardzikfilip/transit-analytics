package com.transitanalytics.buslanes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BusLanesTest {

    private static final String ANSWER = """
            {"elements":[{"type":"way","id":11,"tags":{"name":"Lipowa","oneway":"yes","bus:lanes":"|designated"},
             "geometry":[{"lat":53.13,"lon":23.15},{"lat":53.13,"lon":23.16}]}]}""";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T03:15:00Z"), ZoneOffset.UTC);
    private final JsonMapper json = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private BusLaneProperties properties(String file) {
        return new BusLaneProperties(file, "-", List.of("https://one.example/api/interpreter",
                "https://two.example/api/interpreter"), Duration.ofSeconds(5), 2910925, 15, 45,
                new BusLaneProperties.Official(24, "2025-01-28", "City", "https://example.org"));
    }

    @Test
    void startsWithTheBuiltInLanes() {
        BusLanes lanes = new BusLanes(properties(dir.resolve("none.json").toString()), RestClient.create(), json,
                CLOCK);

        BusLanes.Snapshot snapshot = lanes.current();
        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.data().lanes()).hasSizeGreaterThan(300);
        assertThat(snapshot.data().laneKm()).isCloseTo(24.8, within(0.5));
        assertThat(snapshot.data().source()).contains("OpenStreetMap");
        assertThat(snapshot.stretches().all()).isNotEmpty();
        // A lane of Al. Piłsudskiego, eastbound by the Opera.
        assertThat(snapshot.data().lanes()).anyMatch(l -> "Aleja Józefa Piłsudskiego".equals(l.name()));
    }

    @Test
    void refreshesFromTheFirstServerThatAnswersAndKeepsTheLanes() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://one.example/api/interpreter")).andRespond(withServerError());
        server.expect(requestTo("https://two.example/api/interpreter"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("data=%5Bout%3Ajson%5D")))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        Path file = dir.resolve("config/map/bus-lanes.json");
        BusLanes lanes = new BusLanes(properties(file.toString()), builder.build(), json, CLOCK);

        BusLanes.Snapshot fresh = lanes.refresh();

        server.verify();
        assertThat(fresh.version()).isEqualTo(2);
        assertThat(fresh.data().lanes()).singleElement().extracting(BusLane::name).isEqualTo("Lipowa");
        assertThat(fresh.data().source()).isEqualTo("OpenStreetMap via two.example (© OpenStreetMap contributors, ODbL)");
        assertThat(fresh.data().fetchedAt()).isEqualTo(CLOCK.instant());
        assertThat(lanes.current()).isSameAs(fresh);
        assertThat(Files.readString(file)).contains("\"fetchedAt\":\"2026-12-01T03:15:00Z\"");
        // Saved: used from then on, also after a restart.
        BusLanes restarted = new BusLanes(properties(file.toString()), RestClient.create(), json, CLOCK);
        assertThat(restarted.current().data().lanes()).hasSize(1);
    }

    @Test
    void keepsTheLanesWhenNoServerAnswers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://one.example/api/interpreter")).andRespond(withServerError());
        server.expect(requestTo("https://two.example/api/interpreter"))
                .andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://one.example/api/interpreter")).andRespond(withServerError());
        server.expect(requestTo("https://two.example/api/interpreter")).andRespond(withServerError());
        BusLanes lanes = new BusLanes(properties(""), builder.build(), json, CLOCK);
        BusLanes.Snapshot before = lanes.current();

        assertThatThrownBy(lanes::refresh).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Overpass server");
        assertThat(lanes.current()).isSameAs(before);
        // On schedule a failure is only logged.
        lanes.refreshOnSchedule();
        assertThat(lanes.current()).isSameAs(before);
        server.verify();
    }

    @Test
    void fallsBackToTheBuiltInLanesWhenTheSavedFileIsUnreadable() throws Exception {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "not json");
        BusLanes lanes = new BusLanes(properties(file.toString()), RestClient.create(), json, CLOCK);
        assertThat(lanes.current().data().lanes()).hasSizeGreaterThan(300);
    }
}
