package com.transitanalytics.gtfs;

import com.transitanalytics.common.NotFoundException;
import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.TimetableFeed;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Imports timetables (GTFS): Białystok's from komunikacja.bialystok.pl (the main one), and other operators' routes
 * configured under {@code app.gtfs.extra}, each into its own share of the timetable. One import runs at a time. A
 * download that is the same as the feed's last import is not imported again.
 */
@Service
public class GtfsFeedService {

    /** The main feed: Białystok's timetable. */
    static final String FEED = TimetableFeed.MAIN;

    private static final Logger log = LoggerFactory.getLogger(GtfsFeedService.class);

    private final GtfsDownloader downloader;
    private final GtfsImporter importer;
    private final GtfsProperties properties;
    private final ObservationRegistry observations;
    private final ApplicationEventPublisher events;
    private final ImportedFeeds imported;
    private final AtomicBoolean running = new AtomicBoolean();

    public GtfsFeedService(GtfsDownloader downloader, GtfsImporter importer, GtfsProperties properties,
                           ObservationRegistry observations, ApplicationEventPublisher events,
                           ImportedFeeds imported) {
        this.downloader = downloader;
        this.importer = importer;
        this.properties = properties;
        this.observations = observations;
        this.events = events;
        this.imported = imported;
    }

    /**
     * A feed that can be imported.
     *
     * @param key  {@value #FEED} or a key under {@code app.gtfs.extra}
     * @param last its last import; null if it has none
     */
    public record Feed(String key, String name, String source, ImportedFeeds.Last last) {
    }

    /** Every feed that can be imported, Białystok's first. */
    public List<Feed> feeds() {
        Map<String, ImportedFeeds.Last> last = imported.all();
        List<Feed> feeds = new ArrayList<>();
        if (properties.bialystok() != null) {
            feeds.add(new Feed(FEED, properties.bialystok().name(), properties.bialystok().url(), last.get(FEED)));
        }
        new TreeMap<>(properties.extra()).forEach(
                (key, feed) -> feeds.add(new Feed(key, feed.name(), feed.url(), last.get(key))));
        return feeds;
    }

    /** Downloads Białystok's current feed and replaces the main timetable with it. */
    public GtfsImportResult importFeed() {
        return importFeed(FEED);
    }

    /**
     * Downloads a feed ({@value #FEED}, or a key under {@code app.gtfs.extra}) and replaces what it loaded before
     * with it. Only one import runs at a time.
     *
     * @throws NotFoundException             if there is no such feed
     * @throws GtfsImportInProgressException if an import is already running
     */
    public GtfsImportResult importFeed(String key) {
        GtfsProperties.Feed feed = properties.feed(key);
        if (feed == null) {
            throw new NotFoundException("GTFS feed", key);
        }
        if (!running.compareAndSet(false, true)) {
            throw new GtfsImportInProgressException(key);
        }
        try {
            // Shows up as a "gtfs.import" span in traces and a timer in metrics.
            return Observation.createNotStarted("gtfs.import", observations)
                    .lowCardinalityKeyValue("feed", key)
                    .observe(() -> downloadAndLoad(key, feed));
        } finally {
            running.set(false);
        }
    }

    /** The other operators' feeds, imported again on schedule (they cover a couple of weeks at a time). */
    @Scheduled(cron = "${app.gtfs.extra-refresh-cron:-}", zone = CityTime.ZONE_ID)
    void refreshExtra() {
        for (String key : properties.extra().keySet()) {
            try {
                importFeed(key);
            } catch (RuntimeException ex) {
                log.warn("GTFS feed {} not refreshed: {}", key, ex.getMessage());
            }
        }
    }

    private GtfsImportResult downloadAndLoad(String key, GtfsProperties.Feed feed) {
        Instant start = Instant.now();
        String url = feed.url();
        Path file = downloader.download(url);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            long bytes = Files.size(file);
            String fingerprint = ImportedFeeds.fingerprint(zip, feed.only());
            ImportedFeeds.Last last = imported.last(key);
            if (last != null && last.fingerprint().equals(fingerprint)) {
                log.info("GTFS feed {} is the same as imported at {}: not imported again", key, last.importedAt());
                return new GtfsImportResult(key, url, bytes, last.importedAt(),
                        Duration.between(start, Instant.now()).toMillis(), Map.of(), Map.of(), List.of(), true);
            }
            GtfsImporter.Loaded loaded = feed.only() == null ? importer.load(zip, key)
                    : importer.load(zip, key, feed.only());
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("Imported GTFS feed {} ({} bytes) in {} ms: {}; skipped {}; ignored files {}",
                    key, bytes, ms, loaded.rows(), loaded.skipped(), loaded.ignoredFiles());
            Instant now = Instant.now();
            imported.remember(key, new ImportedFeeds.Last(fingerprint, now, bytes));
            // Whatever depends on the timetable (the routes' classes) is worked out again.
            events.publishEvent(new GtfsImported(key, now));
            return new GtfsImportResult(key, url, bytes, now, ms, loaded.rows(), loaded.skipped(),
                    loaded.ignoredFiles(), false);
        } catch (ZipException ex) {
            throw new GtfsFeedException("The download is not a valid zip file", ex);
        } catch (IOException ex) {
            throw new GtfsFeedException("Could not read the feed: " + ex.getMessage(), ex);
        } finally {
            GtfsDownloader.deleteQuietly(file);
        }
    }
}
