package com.transitanalytics.gtfs;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.zbiorkom.BialystokFeed;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

/**
 * GTFS feeds the app can import: Białystok's (the main timetable) and other operators' lines ({@code extra}), each
 * loaded into its own share of the timetable (its rows' {@code feed} is the key, {@code bialystok} for the main one).
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
        // The city's buses are on the live feed as BKM, whatever a settings override leaves out.
        if (bialystok != null && bialystok.agency() == null) {
            bialystok = new Feed(bialystok.url(), bialystok.name(), BialystokFeed.CITY_AGENCY);
        }
    }

    /**
     * @param url    address of the feed's zip file
     * @param name   what the feed is, for people
     * @param agency the operator its buses have on the live feed (e.g. "WSCHODEXPRESS"), so a line's recorded runs
     *               can be told from another operator's line of the same number; null when its buses aren't on it
     */
    public record Feed(String url, String name, String agency) {
    }

    /** The feed with this key ({@code bialystok} or a key under {@code extra}); null if there is none. */
    public Feed feed(String key) {
        return TimetableFeed.MAIN.equals(key) ? bialystok : extra.get(key);
    }
}
