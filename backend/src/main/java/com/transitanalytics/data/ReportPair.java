package com.transitanalytics.data;

import java.time.Duration;

/**
 * Two consecutive reports of a bus on the same route ({@link Reports#pairs}): where it was, and where it was next.
 *
 * @param bearing the bus's heading at the first report, as the feed gives it
 * @param stop    the stop the bus was heading to at the first report
 */
public record ReportPair(Report from, Report to, Integer bearing, String stop) {

    /** Seconds between the two reports. */
    public double seconds() {
        return Duration.between(from.time(), to.time()).toMillis() / 1000.0;
    }
}
