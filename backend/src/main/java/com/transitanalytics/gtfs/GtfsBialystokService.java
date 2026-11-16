package com.transitanalytics.gtfs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Imports Białystok's public transport timetable (GTFS) from komunikacja.bialystok.pl. */
@Service
public class GtfsBialystokService {

    static final String FEED = "bialystok";

    private static final Logger log = LoggerFactory.getLogger(GtfsBialystokService.class);

    private final GtfsDownloader downloader;
    private final GtfsImporter importer;
    private final GtfsProperties properties;
    private final ObservationRegistry observations;
    private final AtomicBoolean running = new AtomicBoolean();

    public GtfsBialystokService(GtfsDownloader downloader, GtfsImporter importer, GtfsProperties properties,
                                ObservationRegistry observations) {
        this.downloader = downloader;
        this.importer = importer;
        this.properties = properties;
        this.observations = observations;
    }

    /**
     * Downloads the current feed and replaces the timetable with it. Only one import runs at a time.
     *
     * @throws GtfsImportInProgressException if an import is already running
     */
    public GtfsImportResult importFeed() {
        if (!running.compareAndSet(false, true)) {
            throw new GtfsImportInProgressException(FEED);
        }
        try {
            // Shows up as a "gtfs.import" span in traces and a timer in metrics.
            return Observation.createNotStarted("gtfs.import", observations)
                    .lowCardinalityKeyValue("feed", FEED)
                    .observe(this::downloadAndLoad);
        } finally {
            running.set(false);
        }
    }

    private GtfsImportResult downloadAndLoad() {
        Instant start = Instant.now();
        String url = properties.bialystok().url();
        Path file = downloader.download(url);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            long bytes = Files.size(file);
            GtfsImporter.Loaded loaded = importer.load(zip);
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("Imported GTFS feed {} ({} bytes) in {} ms: {}; skipped {}; ignored files {}",
                    FEED, bytes, ms, loaded.rows(), loaded.skipped(), loaded.ignoredFiles());
            return new GtfsImportResult(FEED, url, bytes, Instant.now(), ms, loaded.rows(), loaded.skipped(),
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
