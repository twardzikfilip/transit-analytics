package com.transitanalytics.geo;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The reference data about places: borders, woods, bus lanes and residents, in {@code places/} on the classpath (see
 * its README). A file of the same path under {@code ./config/places/} (next to the jar or where the app is started)
 * overrides the built-in one, so figures can be corrected without rebuilding.
 */
public final class PlaceData {

    /** Where the built-in files are on the classpath. */
    public static final String BUILT_IN = "places/";
    /** Where overriding files are looked for. */
    public static final Path OVERRIDES = Path.of("config", "places");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PlaceData() {
    }

    /** Opens a file, e.g. "population/districts.csv": the override when there is one, else the built-in one. */
    public static InputStream open(String path) {
        return open(OVERRIDES, path);
    }

    static InputStream open(Path overrides, String path) {
        try {
            Path override = overrides.resolve(path);
            if (Files.isRegularFile(override)) {
                return Files.newInputStream(override);
            }
            return new ClassPathResource(BUILT_IN + path).getInputStream();
        } catch (IOException ex) {
            throw new UncheckedIOException("Place data " + path + " can't be read", ex);
        }
    }

    /** Reads a JSON file into {@code type}. */
    public static <T> T json(String path, Class<T> type) {
        return json(OVERRIDES, path, type);
    }

    static <T> T json(Path overrides, String path, Class<T> type) {
        try (InputStream in = open(overrides, path)) {
            return JSON.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Place data " + path + " can't be read", ex);
        }
    }

    /** Reads a CSV file (see {@link CsvTable}). */
    public static CsvTable csv(String path) {
        return csv(OVERRIDES, path);
    }

    static CsvTable csv(Path overrides, String path) {
        try (InputStream in = open(overrides, path)) {
            return CsvTable.read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("Place data " + path + " can't be read", ex);
        }
    }
}
