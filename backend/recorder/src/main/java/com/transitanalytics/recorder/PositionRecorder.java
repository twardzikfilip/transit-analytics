package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.recording.RawArchive;
import com.transitanalytics.recording.RecordingWindow;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.FeedPosition;
import com.transitanalytics.zbiorkom.LiveSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the zbiorkom.live Białystok feed on a schedule (every 10 seconds, 03:30–01:30 Warsaw time). Each response is
 * archived first, as received, so it survives whatever comes next; then its positions are stored in
 * {@code position_sample}. When the database can't take them, the response goes to the {@link Spool} and is stored
 * once it is back. The latest positions are kept for the backend's Live map.
 */
@Component
public class PositionRecorder {

    static final String SOURCE = "zbiorkom-bialystok";
    /** The Live map gets a fresh snapshot when the latest is older than this (e.g. outside recording hours). */
    static final Duration LIVE_MAX_AGE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(PositionRecorder.class);

    private final BialystokFeed feed;
    private final PositionSampleWriter writer;
    private final RawArchive archive;
    private final Spool spool;
    private final RecordingWindow window;
    private final RecordingProperties properties;
    private final Clock clock;

    private final Counter samples;
    private final Counter failures;
    private final Timer pollTimer;
    private final AtomicInteger lastVehicles = new AtomicInteger();
    private volatile Instant lastPoll;
    private volatile Instant lastStored;
    private volatile LiveSnapshot latest;

    public PositionRecorder(BialystokFeed feed, PositionSampleWriter writer, RawArchive archive, Spool spool,
                            RecordingWindow window, RecordingProperties properties, Clock clock,
                            MeterRegistry meters) {
        this.feed = feed;
        this.writer = writer;
        this.archive = archive;
        this.spool = spool;
        this.window = window;
        this.properties = properties;
        this.clock = clock;
        this.samples = Counter.builder("transit.recording.samples").description("Position rows stored")
                .tag("source", SOURCE).register(meters);
        this.failures = Counter.builder("transit.recording.failures").description("Polls that failed")
                .tag("source", SOURCE).register(meters);
        this.pollTimer = Timer.builder("transit.recording.poll").description("Fetch, archive and store one snapshot")
                .tag("source", SOURCE).register(meters);
        // A strong reference: Micrometer otherwise holds the gauge's object weakly, and it reads NaN once collected.
        Gauge.builder("transit.recording.vehicles", lastVehicles, AtomicInteger::get).strongReference(true)
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
        pollTimer.record(() -> poll(now));
        return true;
    }

    private void poll(Instant fetchedAt) {
        byte[] raw;
        try {
            raw = feed.fetchRaw();
        } catch (RuntimeException ex) {
            // Nothing to keep; the next poll, 10 seconds on, tries again.
            failures.increment();
            log.warn("Polling the feed failed: {}", ex.getMessage());
            return;
        }
        Path file = null;
        try {
            file = archive.write(SOURCE, fetchedAt, raw);
        } catch (RuntimeException ex) {
            log.error("Archiving the response failed (it is still stored if the database takes it): {}",
                    ex.getMessage());
        }
        try {
            List<FeedPosition> positions = feed.positions(raw);
            int stored = writer.write(positions, fetchedAt);
            samples.increment(stored);
            lastVehicles.set(positions.size());
            lastStored = fetchedAt;
            latest = new LiveSnapshot(fetchedAt, positions);
            log.debug("Recorded {} of {} positions", stored, positions.size());
        } catch (RuntimeException ex) {
            failures.increment();
            if (file != null) {
                spool.add(file, fetchedAt);
                log.warn("Storing positions failed, kept for later: {}", ex.getMessage());
            } else {
                log.error("Storing positions failed and the response couldn't be archived; lost: {}",
                        ex.getMessage());
            }
            latest = new LiveSnapshot(fetchedAt, withoutIds(raw));
        }
    }

    private List<FeedPosition> withoutIds(byte[] raw) {
        try {
            return feed.positionsWithoutIds(raw);
        } catch (RuntimeException ex) {
            return latest == null ? List.of() : latest.positions();
        }
    }

    /**
     * The latest positions, for the Live map. When they are older than {@link #LIVE_MAX_AGE} (outside recording
     * hours, or polls failing), the feed is asked now, at most once every {@code min-interval}; nothing is stored then.
     */
    public synchronized LiveSnapshot live() {
        Instant now = clock.instant();
        LiveSnapshot current = latest;
        boolean fresh = current != null && current.fetchedAt().isAfter(now.minus(LIVE_MAX_AGE));
        boolean asked = lastPoll != null && lastPoll.isAfter(now.minus(properties.minInterval()));
        if (fresh || asked && current != null) {
            return current;
        }
        lastPoll = now;
        byte[] raw = feed.fetchRaw();
        List<FeedPosition> positions;
        try {
            positions = feed.positions(raw);
        } catch (RuntimeException ex) {
            positions = feed.positionsWithoutIds(raw);
        }
        latest = new LiveSnapshot(now, positions);
        return latest;
    }

    /** When the last poll was attempted; null before the first. */
    public Instant lastPoll() {
        return lastPoll;
    }

    /** When positions were last stored; null before the first. */
    public Instant lastStored() {
        return lastStored;
    }
}
