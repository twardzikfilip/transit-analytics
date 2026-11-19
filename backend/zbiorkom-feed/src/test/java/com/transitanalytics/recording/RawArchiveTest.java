package com.transitanalytics.recording;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RawArchiveTest {

    @TempDir
    Path root;

    @Test
    void writesGzippedResponseUnderUtcDateAndKeepsTheFirstCopy() throws IOException {
        RawArchive archive = new RawArchive(root);
        Instant at = Instant.parse("2026-11-16T23:30:05Z"); // already the 17th in Warsaw; the name stays UTC

        Path file = archive.write("zbiorkom-bialystok", at, "{\"positions\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThat(root.relativize(file).toString().replace('\\', '/'))
                .isEqualTo("zbiorkom-bialystok/2026/11/16/20261116T233005Z.json.gz");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"positions\":[]}");
        }

        archive.write("zbiorkom-bialystok", at, "other".getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"positions\":[]}");
        }
        try (var files = Files.list(file.getParent())) {
            assertThat(files).as("no leftover temp files").hasSize(1);
        }
    }
}
