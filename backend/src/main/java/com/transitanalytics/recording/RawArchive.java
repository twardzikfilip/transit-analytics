package com.transitanalytics.recording;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps every feed response as received, gzipped (~13 KiB per Białystok snapshot), so the data can be re-processed
 * later, e.g. to load a field the database doesn't store. Layout, in UTC so names are unique all year round:
 * {@code <root>/<source>/2026/11/16/20261116T121208Z.json.gz}.
 */
public class RawArchive {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path root;

    public RawArchive(Path root) {
        this.root = root;
    }

    public Path pathFor(String source, Instant fetchedAt) {
        return root.resolve(source).resolve(DAY.format(fetchedAt)).resolve(NAME.format(fetchedAt) + ".json.gz");
    }

    /**
     * Writes the response unless a file for the same moment exists already (re-imports are no-ops).
     *
     * @return the file
     */
    public Path write(String source, Instant fetchedAt, byte[] response) {
        Path file = pathFor(source, fetchedAt);
        if (Files.exists(file)) {
            return file;
        }
        try {
            Files.createDirectories(file.getParent());
            // Write to a temporary file and move it into place, so a crash never leaves a half-written archive file.
            Path tmp = Files.createTempFile(file.getParent(), ".", ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(response);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not archive " + file, ex);
        }
    }
}
