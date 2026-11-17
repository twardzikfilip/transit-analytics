package com.transitanalytics.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/** The time windows of the reports over recorded positions, ending now. */
public enum TimeWindow {
    MINUTES_15("15m", Duration.ofMinutes(15)),
    HOUR("1h", Duration.ofHours(1)),
    HOURS_3("3h", Duration.ofHours(3)),
    HOURS_6("6h", Duration.ofHours(6)),
    DAY("day", Duration.ofDays(1)),
    WEEK("week", Duration.ofDays(7)),
    MONTH("month", Duration.ofDays(30));

    private final String key;
    final Duration length;

    TimeWindow(String key, Duration length) {
        this.key = key;
        this.length = length;
    }

    /** By its key ("15m", "1h", "3h", "6h", "day", "week" or "month"), in any case. */
    public static TimeWindow of(String value) {
        return Arrays.stream(values()).filter(w -> w.key.equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("window must be one of " + Arrays.stream(values())
                        .map(TimeWindow::key).collect(Collectors.joining(", "))));
    }

    /** The name in the API: "15m", "1h", "3h", "6h", "day", "week" or "month". */
    public String key() {
        return key;
    }
}
