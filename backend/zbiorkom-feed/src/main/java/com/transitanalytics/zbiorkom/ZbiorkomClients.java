package com.transitanalytics.zbiorkom;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Builds the zbiorkom.live client with the configured address and timeouts. */
public final class ZbiorkomClients {

    private ZbiorkomClients() {
    }

    /**
     * The client for the feed: {@code builder} (Boot's, so every call is traced) with the configured timeouts and
     * address.
     */
    public static ZbiorkomClient create(RestClient.Builder builder, ZbiorkomProperties properties, JsonMapper json) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.connectTimeout());
        requests.setReadTimeout(properties.readTimeout());
        return withoutTimeouts(builder.requestFactory(requests), properties, json);
    }

    /** The same, keeping the builder's own request factory (e.g. a test's mock server). */
    public static ZbiorkomClient withoutTimeouts(RestClient.Builder builder, ZbiorkomProperties properties,
                                                 JsonMapper json) {
        return new ZbiorkomClient(builder.baseUrl(properties.baseUrl()).build(), json);
    }
}
