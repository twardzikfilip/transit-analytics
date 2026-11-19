package com.transitanalytics.zbiorkom;

import org.springframework.web.client.RestClient;
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
}
