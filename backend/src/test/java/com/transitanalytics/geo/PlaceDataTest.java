package com.transitanalytics.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceDataTest {

    @Test
    void aFileInTheOverrideFolderWinsOverTheBuiltInOne(@TempDir Path dir) throws Exception {
        CsvTable builtIn = PlaceData.csv(dir, "population/city.csv");
        assertThat(builtIn.rows().get(0).integer("population")).isEqualTo(289_217);

        Path file = dir.resolve("population/city.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "name,population,date,source\nBiałystok,300000,2026-12-31,test\n");
        assertThat(PlaceData.csv(dir, "population/city.csv").rows().get(0).integer("population")).isEqualTo(300_000);
    }
}
