package com.transitanalytics.zbiorkom;

import com.transitanalytics.dto.VersionDto;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the latest snapshot from transit-recorder ({@code GET /live/bialystok/positions}), which polls the feed every
 * 10 seconds, so the Live map doesn't call zbiorkom.live itself.
 */
public class RecorderClient {

    private final RestClient client;
    private final JsonMapper json;

    /** @param client with the recorder's address and short timeouts (see {@link ZbiorkomConfig}) */
    public RecorderClient(RestClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    /** The recorder's latest snapshot; throws when the recorder can't be reached or answers with an error. */
    public LiveSnapshot latest() {
        byte[] body = client.get().uri("/live/bialystok/positions").retrieve().body(byte[].class);
        return json.readValue(body, LiveSnapshot.class);
    }

    /** The recorder's version and release date; null when it doesn't answer (or is one from before versions). */
    public VersionDto.Release release() {
        try {
            byte[] body = client.get().uri("/actuator/info").retrieve().body(byte[].class);
            JsonNode build = json.readTree(body).path("build");
            String version = build.path("version").asString(null);
            return version == null ? null : new VersionDto.Release(version, build.path("released").asString(null));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
