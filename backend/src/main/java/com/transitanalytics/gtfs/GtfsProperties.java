package com.transitanalytics.gtfs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

/**
 * GTFS feeds the app can import: Białystok's (the main timetable) and other operators' lines ({@code extra}), each
 * loaded into its own share of the timetable.
 *
 * @param maxDownloadSize   a download larger than this is aborted
 * @param extra             other feeds by key (used in {@code POST /api/gtfs/{key}/import})
 * @param extraRefreshCron  when the other feeds are imported again (they are short-lived); "-" never
 */
@ConfigurationProperties("app.gtfs")
public record GtfsProperties(Duration connectTimeout, Duration readTimeout, DataSize maxDownloadSize,
                             Feed bialystok, Map<String, Feed> extra, String extraRefreshCron) {

    public GtfsProperties {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /**
     * @param url    address of the feed's zip file
     * @param prefix put in front of the feed's ids (e.g. "pksnova:"); empty for the main feed
     * @param name   what the feed is, for people
     */
    public record Feed(String url, String prefix, String name) {

        public Feed {
            prefix = prefix == null ? "" : prefix;
        }
    }
}
