package com.transitanalytics.service;

import com.transitanalytics.gtfs.GtfsProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordedFeedsTest {

    @Test
    void judgesOnlyTheFeedsWhoseBusesAreOnTheLiveFeed() {
        Map<String, GtfsProperties.Feed> extra = new LinkedHashMap<>();
        extra.put("pksnova-500", new GtfsProperties.Feed("https://example.org/pks.zip", "PKS Nova", null));
        extra.put("wschodexpress", new GtfsProperties.Feed("https://example.org/we.zip", "Wschód Express",
                "WSCHODEXPRESS"));
        // The city's feed without `agency` (a settings override leaving it out) is still on the live feed as BKM.
        GtfsProperties feeds = new GtfsProperties(null, null, null,
                new GtfsProperties.Feed("https://example.org/bkm.zip", null, null), extra, "-");

        assertThat(feeds.feed("bialystok").agency()).isEqualTo("BKM");
        // PKS Nova's buses aren't on the live feed: its trips would all look cancelled.
        assertThat(TripAnomalyService.recordedFeeds(feeds)).containsExactlyInAnyOrder("bialystok", "wschodexpress");
    }
}
