package com.transitanalytics.gtfs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * GTFS feeds the app can import.
 *
 * @param maxDownloadSize a download larger than this is aborted
 */
@ConfigurationProperties("app.gtfs")
public record GtfsProperties(Duration connectTimeout, Duration readTimeout, DataSize maxDownloadSize,
                             Feed bialystok) {

    /** @param url address of the feed's zip file */
    public record Feed(String url) {
    }
}
