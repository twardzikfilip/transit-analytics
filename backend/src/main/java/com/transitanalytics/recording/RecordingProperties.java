package com.transitanalytics.recording;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Recording of zbiorkom.live vehicle positions into {@code position_sample}.
 *
 * @param enabled     poll the feed on {@code cron}
 * @param cron        when to poll (Spring cron, seconds first); default every 20 seconds
 * @param zone        time zone of {@code cron} and the window
 * @param windowStart first time of day to record (inclusive)
 * @param windowEnd   last time of day to record (inclusive); may be before {@code windowStart} to span midnight
 * @param minInterval polls closer together than this are skipped, so the feed is never polled more often than
 *                    planned
 * @param archiveDir  where raw responses are kept, gzipped
 * @param importDir   set to backfill saved responses from this folder; the app then imports and exits
 */
@ConfigurationProperties("app.recording")
public record RecordingProperties(boolean enabled, String cron, ZoneId zone, LocalTime windowStart,
                                  LocalTime windowEnd, Duration minInterval, String archiveDir, String importDir) {

    public boolean importMode() {
        return importDir != null && !importDir.isBlank();
    }
}
