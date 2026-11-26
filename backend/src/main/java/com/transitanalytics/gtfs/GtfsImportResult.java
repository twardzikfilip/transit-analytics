package com.transitanalytics.gtfs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a GTFS import.
 *
 * @param rows         rows now in each table (agency, route, stop, trip, stop_time, calendar_date, shape_point)
 * @param skipped      feed rows per file that were not loaded because they point at a missing route, trip or stop
 * @param ignoredFiles files in the feed this app does not load
 * @param unchanged    the download was the same as the last import's, so nothing was imported; {@code importedAt} is
 *                     then that import's time, and the row counts, skipped rows and ignored files are empty
 */
public record GtfsImportResult(String feed, String source, long downloadedBytes, Instant importedAt, long durationMs,
                               Map<String, Long> rows, Map<String, Long> skipped, List<String> ignoredFiles,
                               boolean unchanged) {
}
