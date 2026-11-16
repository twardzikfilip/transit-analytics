package com.transitanalytics.zbiorkom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings for the zbiorkom.live open API. */
@ConfigurationProperties("app.zbiorkom")
public record ZbiorkomProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, Bialystok bialystok) {

    /** @param bbox bounding box "minLon,minLat,maxLon,maxLat" the positions are requested for */
    public record Bialystok(String bbox) {
    }
}
