package com.transitanalytics.zbiorkom;

import java.time.Instant;
import java.util.List;

/** The positions of one poll of the feed and when it was made: what the recorder serves to the backend. */
public record LiveSnapshot(Instant fetchedAt, List<FeedPosition> positions) {

    public LiveSnapshot {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
