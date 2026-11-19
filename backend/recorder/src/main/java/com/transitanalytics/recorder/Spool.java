package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.FeedPosition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Responses archived but not stored yet, because the database was down: listed in {@code <spool-dir>/pending}, one
 * line per response ({@code <fetched at>\t<archive file>}), oldest first. Every 30 seconds, and at startup, they are
 * stored in turn; one that fails stops the round (the database is still down) and stays listed. Storing twice adds
 * nothing (a report already stored is skipped), so a round cut short is safe to repeat.
 */
@Component
public class Spool {

    private static final Logger log = LoggerFactory.getLogger(Spool.class);

    private final Path pending;
    private final BialystokFeed feed;
    private final PositionSampleWriter writer;

    public Spool(RecordingProperties properties, BialystokFeed feed, PositionSampleWriter writer,
                 MeterRegistry meters) {
        this.pending = Path.of(properties.spoolDir()).toAbsolutePath().resolve("pending");
        this.feed = feed;
        this.writer = writer;
        Gauge.builder("transit.recording.spooled", this, Spool::size).strongReference(true)
                .description("Responses waiting to be stored").tag("source", PositionRecorder.SOURCE)
                .register(meters);
    }

    /** Lists an archived response to store later. */
    public synchronized void add(Path archived, Instant fetchedAt) {
        try {
            Files.createDirectories(pending.getParent());
            Files.writeString(pending, fetchedAt + "\t" + archived.toAbsolutePath() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not spool " + archived, ex);
        }
    }

    /** Responses waiting. */
    public synchronized int size() {
        return lines().size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        replay();
    }

    /**
     * Stores the waiting responses, oldest first, until one fails.
     *
     * @return how many were stored
     */
    @Scheduled(fixedDelayString = "${app.recording.spool-every:30s}", initialDelayString = "${app.recording.spool-every:30s}")
    public synchronized int replay() {
        List<String> lines = lines();
        int done = 0;
        long rows = 0;
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            try {
                Instant fetchedAt = Instant.parse(parts[0]);
                Path file = Path.of(parts[1]);
                if (Files.exists(file)) {
                    List<FeedPosition> positions = feed.positions(BialystokFeed.read(file));
                    rows += writer.write(positions, fetchedAt);
                } else {
                    log.warn("Spooled response {} is gone; skipped", file);
                }
                done++;
            } catch (RuntimeException ex) {
                if (parts.length < 2 || ex instanceof java.time.format.DateTimeParseException) {
                    log.warn("Unreadable spool line skipped: {}", line);
                    done++;
                    continue;
                }
                log.debug("Spool round stopped: {}", ex.getMessage());
                break;
            }
        }
        if (done > 0) {
            write(lines.subList(done, lines.size()));
            log.info("Stored {} spooled responses ({} new rows); {} still waiting", done, rows, lines.size() - done);
        }
        return done;
    }

    private List<String> lines() {
        try {
            return Files.exists(pending)
                    ? Files.readAllLines(pending, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList()
                    : List.of();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void write(List<String> remaining) {
        try {
            if (remaining.isEmpty()) {
                Files.deleteIfExists(pending);
                return;
            }
            Path tmp = Files.createTempFile(pending.getParent(), "pending", ".tmp");
            Files.write(tmp, new ArrayList<>(remaining), StandardCharsets.UTF_8);
            Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
