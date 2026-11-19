package com.transitanalytics.recorder;

import com.transitanalytics.recording.RecordingWindow;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether recording works: during recording hours, a poll in the last minute that stored its positions (or was
 * spooled for later). Docker's health check and the upgrade script wait on it.
 */
@Component("recording")
public class RecordingHealth implements HealthIndicator {

    /** No poll, or nothing stored, for this long during recording hours is unhealthy. */
    static final Duration MAX_SILENCE = Duration.ofSeconds(60);

    private final PositionRecorder recorder;
    private final Spool spool;
    private final RecordingWindow window;
    private final RecordingProperties properties;
    private final Clock clock;
    private final Instant started;

    public RecordingHealth(PositionRecorder recorder, Spool spool, RecordingWindow window,
                           RecordingProperties properties, Clock clock) {
        this.recorder = recorder;
        this.spool = spool;
        this.window = window;
        this.properties = properties;
        this.clock = clock;
        this.started = clock.instant();
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Instant polled = recorder.lastPoll();
        Instant stored = recorder.lastStored();
        Health.Builder health = Health.up();
        if (properties.enabled() && !properties.importMode() && window.contains(now)) {
            Instant since = now.minus(MAX_SILENCE);
            // Just started: give the first polls a minute.
            boolean warmingUp = started.isAfter(since);
            if (!warmingUp && (polled == null || polled.isBefore(since))) {
                health = Health.down().withDetail("reason", "no poll in the last minute");
            } else if (!warmingUp && (stored == null || stored.isBefore(since)) && spool.size() == 0) {
                health = Health.down().withDetail("reason", "nothing stored in the last minute");
            }
        } else {
            health.withDetail("recording", "outside recording hours or off");
        }
        return health.withDetail("lastPoll", String.valueOf(polled)).withDetail("lastStored", String.valueOf(stored))
                .withDetail("spooled", spool.size()).build();
    }
}
