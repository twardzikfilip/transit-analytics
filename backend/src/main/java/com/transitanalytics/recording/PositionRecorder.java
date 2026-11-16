package com.transitanalytics.recording;

import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the zbiorkom.live Białystok feed on a schedule (default: every minute, 03:30–01:30 Warsaw time), keeps the
 * raw response in the archive and stores every position in {@code position_sample}.
 */
@Component
public class PositionRecorder {

    static final String SOURCE = "zbiorkom-bialystok";

    private static final Logger log = LoggerFactory.getLogger(PositionRecorder.class);

    private final ZbiorkomBialystokService feed;
    private final PositionSampleWriter writer;
    private final RawArchive archive;
    private final RecordingWindow window;
    private final RecordingProperties properties;
    private final Clock clock;

    private final Counter samples;
    private final Counter failures;
    private final Timer pollTimer;
    private final AtomicInteger lastVehicles = new AtomicInteger();
    private volatile Instant lastPoll;

    public PositionRecorder(ZbiorkomBialystokService feed, PositionSampleWriter writer, RawArchive archive,
                            RecordingWindow window, RecordingProperties properties, Clock clock,
                            MeterRegistry meters) {
        this.feed = feed;
        this.writer = writer;
        this.archive = archive;
        this.window = window;
        this.properties = properties;
        this.clock = clock;
        this.samples = Counter.builder("transit.recording.samples").description("Position rows stored")
                .tag("source", SOURCE).register(meters);
        this.failures = Counter.builder("transit.recording.failures").description("Polls that failed")
                .tag("source", SOURCE).register(meters);
        this.pollTimer = Timer.builder("transit.recording.poll").description("Fetch, archive and store one snapshot")
                .tag("source", SOURCE).register(meters);
        Gauge.builder("transit.recording.vehicles", lastVehicles, AtomicInteger::get)
                .description("Vehicles in the last snapshot").tag("source", SOURCE).register(meters);
    }

    @Scheduled(cron = "${app.recording.cron}", zone = "${app.recording.zone}")
    public void scheduledPoll() {
        pollIfDue();
    }

    /**
     * Polls once, unless recording is off, it is outside the recording hours, or the last poll was too recent.
     *
     * @return whether a poll was made
     */
    boolean pollIfDue() {
        Instant now = clock.instant();
        if (!properties.enabled() || properties.importMode() || !window.contains(now)) {
            return false;
        }
        if (lastPoll != null && Duration.between(lastPoll, now).compareTo(properties.minInterval()) < 0) {
            log.debug("Skipping poll: the last one was at {}", lastPoll);
            return false;
        }
        lastPoll = now;
        pollTimer.record(this::poll);
        return true;
    }

    private void poll() {
        try {
            ZbiorkomBialystokService.Snapshot snapshot = feed.snapshot();
            archive.write(SOURCE, snapshot.fetchedAt(), snapshot.raw());
            int stored = writer.write(snapshot.positions(), snapshot.fetchedAt());
            samples.increment(stored);
            lastVehicles.set(snapshot.positions().size());
            log.debug("Recorded {} of {} positions", stored, snapshot.positions().size());
        } catch (RuntimeException ex) {
            // The next scheduled poll simply tries again.
            failures.increment();
            log.warn("Recording positions failed: {}", ex.getMessage());
        }
    }
}
