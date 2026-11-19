package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.recording.RawArchive;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.FeedPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Backfills saved zbiorkom responses (one API response per file, {@code .json} or {@code .json.gz}) into
 * {@code position_sample} and the raw archive, then exits. Re-running it on the same files adds nothing.
 *
 * <pre>./gradlew :recorder:bootRun --args='--app.recording.import-dir=/path/to/snapshots --server.port=0'</pre>
 * Run it as the database owner ({@code DB_USER=transit}): refreshing the aggregates needs more than the recorder's
 * own role may do.
 */
@Component
public class SnapshotImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotImportRunner.class);

    private final RecordingProperties properties;
    private final BialystokFeed feed;
    private final PositionSampleWriter writer;
    private final RawArchive archive;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;

    public SnapshotImportRunner(RecordingProperties properties, BialystokFeed feed,
                                PositionSampleWriter writer, RawArchive archive, JdbcTemplate jdbc,
                                ApplicationContext context) {
        this.properties = properties;
        this.feed = feed;
        this.writer = writer;
        this.archive = archive;
        this.jdbc = jdbc;
        this.context = context;
    }

    /** Totals of one import. */
    public record Result(int files, int failedFiles, long positions, long stored, Instant first, Instant last) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.importMode()) {
            return;
        }
        Result result = importFrom(Path.of(properties.importDir()));
        log.info("Imported {} files ({} failed): {} positions, {} new rows, {} – {}", result.files(),
                result.failedFiles(), result.positions(), result.stored(), result.first(), result.last());
        System.exit(SpringApplication.exit(context, () -> result.failedFiles() == 0 ? 0 : 1));
    }

    public Result importFrom(Path dir) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")
                            || p.getFileName().toString().endsWith(".json.gz"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read " + dir + ": " + ex.getMessage(), ex);
        }
        log.info("Importing {} snapshot files from {}", files.size(), dir);

        int failed = 0;
        long positions = 0;
        long stored = 0;
        Instant first = null;
        Instant last = null;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                byte[] json = BialystokFeed.read(file);
                List<FeedPosition> snapshot = feed.positions(json);
                // The files don't say when they were fetched; the newest position is the closest we know.
                Instant fetchedAt = snapshot.stream().map(FeedPosition::timestamp).filter(Objects::nonNull)
                        .max(Instant::compareTo).orElse(Files.getLastModifiedTime(file).toInstant());
                archive.write(PositionRecorder.SOURCE, fetchedAt, json);
                stored += writer.write(snapshot, fetchedAt);
                positions += snapshot.size();
                first = first == null || fetchedAt.isBefore(first) ? fetchedAt : first;
                last = last == null || fetchedAt.isAfter(last) ? fetchedAt : last;
            } catch (IOException | RuntimeException ex) {
                failed++;
                log.warn("Skipping {}: {}", file, ex.getMessage());
            }
            if ((i + 1) % 500 == 0) {
                log.info("… {} of {} files", i + 1, files.size());
            }
        }
        if (first != null) {
            refreshAggregates(first, last);
        }
        return new Result(files.size(), failed, positions, stored, first, last);
    }

    /** The aggregates' background policy only covers the last 2 days; refresh the backfilled range explicitly. */
    private void refreshAggregates(Instant from, Instant to) {
        Timestamp start = Timestamp.from(from.minusSeconds(3600));
        Timestamp end = Timestamp.from(to.plusSeconds(3600));
        for (String view : List.of("fleet_5min", "route_delay_hourly")) {
            // The procedure takes "any" for the window bounds, so the types must be spelled out.
            jdbc.update("CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)", view, start, end);
        }
    }
}
