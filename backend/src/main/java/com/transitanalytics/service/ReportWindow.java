package com.transitanalytics.service;

import java.time.Duration;
import java.time.Instant;

/**
 * The time window of a report over recorded positions: {@code [from, to)}. Every report page sends its window as
 * {@code from}/{@code to}; {@link ReportWindows#resolve} fills in what is left out.
 */
public record ReportWindow(Instant from, Instant to) {

    public ReportWindow {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    public Duration length() {
        return Duration.between(from, to);
    }
}
