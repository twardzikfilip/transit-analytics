package com.transitanalytics.zbiorkom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where transit-recorder serves its latest snapshot.
 *
 * @param url     e.g. {@code http://localhost:8081}
 * @param timeout per request (connect and read); default 2 s
 */
@ConfigurationProperties("app.recorder")
public record RecorderProperties(String url, Duration timeout) {

    public RecorderProperties {
        url = url == null || url.isBlank() ? "http://localhost:8081" : url;
        timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
    }
}
