package com.transitanalytics.gtfs;

import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.service.NotFoundException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Imports timetables (GTFS): Białystok's from komunikacja.bialystok.pl (the main one), and other operators' lines
 * configured under {@code app.gtfs.extra}, each into its own share of the timetable. One import runs at a time.
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
    private final AtomicBoolean running = new AtomicBoolean();

    public GtfsFeedService(GtfsDownloader downloader, GtfsImporter importer, GtfsProperties properties,
                                ObservationRegistry observations, ApplicationEventPublisher events) {
        this.downloader = downloader;
        this.importer = importer;
        this.properties = properties;
        this.observations = observations;
        this.events = events;
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
            GtfsImporter.Loaded loaded = feed.only() == null ? importer.load(zip, key)
                    : importer.load(zip, key, feed.only());
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("Imported GTFS feed {} ({} bytes) in {} ms: {}; skipped {}; ignored files {}",
                    key, bytes, ms, loaded.rows(), loaded.skipped(), loaded.ignoredFiles());
            // Whatever depends on the timetable (the lines' classes) is worked out again.
            events.publishEvent(new GtfsImported(key, Instant.now()));
            return new GtfsImportResult(key, url, bytes, Instant.now(), ms, loaded.rows(), loaded.skipped(),
                    loaded.ignoredFiles());
        } catch (ZipException ex) {
            throw new GtfsFeedException("The download is not a valid zip file", ex);
        } catch (IOException ex) {
            throw new GtfsFeedException("Could not read the feed: " + ex.getMessage(), ex);
        } finally {
            GtfsDownloader.deleteQuietly(file);
        }
    }
}
