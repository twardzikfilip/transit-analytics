package com.transitanalytics.gtfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads a tiny feed into the real database. The test transaction is rolled back at the end (the importer joins it),
 * so the timetable in the database is left as it was. Run with INTEGRATION_TESTS=true and the DB up.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false",
        "app.recording.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class GtfsImporterIntegrationTest {

    private static final Map<String, String> FEED = Map.of(
            "agency.txt", """
                    agency_id,agency_name,agency_url,agency_timezone
                    BKM,Test agency,https://example.org,Europe/Warsaw
                    """,
            "routes.txt", """
                    route_id,agency_id,route_short_name,route_long_name,route_type,route_color
                    R1,,,1,3,ff0000
                    R2,BKM,N1,Night,3,
                    """,
            "stops.txt", """
                    stop_id,stop_code,stop_name,stop_lat,stop_lon
                    S1,100,"Lipowa, Rynek",53.13,23.15
                    S2,200,Dworzec,53.14,23.16
                    """,
            "trips.txt", """
                    route_id,service_id,trip_id,trip_headsign,direction_id
                    R1,P,T1,Dworzec,0
                    R9,P,T2,Unknown route,1
                    """,
            "stop_times.txt", """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence
                    T1,4:49:00,4:49:00,S1,0
                    T1,24:05:00,24:05:00,S2,1
                    T1,24:06:00,24:06:00,S9,2
                    T2,05:00:00,05:00:00,S1,0
                    """,
            "calendar_dates.txt", """
                    service_id,date,exception_type
                    P,20261116,1
                    """,
            "feed_info.txt", "feed_publisher_name\nTest\n");

    @Autowired
    GtfsImporter importer;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    /**
     * The importer runs ANALYZE, and PostgreSQL keeps those statistics even though the test data is rolled back.
     * Re-analyse the real data so the database isn't left planning queries for this tiny feed.
     */
    @AfterTransaction
    void restoreStatistics() {
        jdbc.execute("ANALYZE agency, route, stop, trip, stop_time, calendar_date, shape_point");
    }

    @Test
    void loadsFeedSkipsBrokenReferencesAndKeepsIdsOnReimport() throws IOException {
        GtfsImporter.Loaded first;
        try (ZipFile zip = zip(FEED)) {
            first = importer.load(zip);
        }

        assertThat(first.rows()).containsEntry("agency", 1L).containsEntry("route", 2L).containsEntry("stop", 2L)
                .containsEntry("trip", 1L).containsEntry("stop_time", 2L).containsEntry("calendar_date", 1L)
                .containsEntry("shape_point", 0L);
        // T2 has an unknown route; its stop time and the one at unknown stop S9 are skipped too.
        assertThat(first.skipped()).containsEntry("trips.txt", 1L).containsEntry("stop_times.txt", 2L);
        assertThat(first.ignoredFiles()).containsExactly("feed_info.txt");

        assertThat(jdbc.queryForObject("SELECT a.gtfs_id FROM route r JOIN agency a ON a.id = r.agency_id "
                + "WHERE r.gtfs_id = 'R1'", String.class)).as("route without agency_id gets the only agency")
                .isEqualTo("BKM");
        assertThat(jdbc.queryForObject("SELECT color FROM route WHERE gtfs_id = 'R1'", String.class))
                .isEqualTo("#FF0000");
        assertThat(jdbc.queryForObject("SELECT name FROM stop WHERE gtfs_id = 'S1'", String.class))
                .isEqualTo("Lipowa, Rynek");
        assertThat(jdbc.queryForList("SELECT departure_time FROM stop_time ORDER BY stop_sequence", String.class))
                .containsExactly("04:49:00", "24:05:00");

        Long tripId = jdbc.queryForObject("SELECT id FROM trip WHERE gtfs_id = 'T1'", Long.class);
        try (ZipFile zip = zip(FEED)) {
            importer.load(zip);
        }
        assertThat(jdbc.queryForObject("SELECT id FROM trip WHERE gtfs_id = 'T1'", Long.class))
                .as("re-import keeps database ids").isEqualTo(tripId);
    }

    @Test
    void rejectsFeedWithoutRequiredFile() throws IOException {
        Map<String, String> feed = new java.util.HashMap<>(FEED);
        feed.remove("stops.txt");
        try (ZipFile zip = zip(feed)) {
            assertThatThrownBy(() -> importer.load(zip)).isInstanceOf(GtfsFeedException.class)
                    .hasMessageContaining("stops.txt");
        }
    }

    @Test
    void rejectsInvalidValues() throws IOException {
        Map<String, String> feed = new java.util.HashMap<>(FEED);
        feed.put("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\nS1,A,north,23.1\n");
        try (ZipFile zip = zip(feed)) {
            assertThatThrownBy(() -> importer.load(zip)).isInstanceOf(GtfsFeedException.class)
                    .hasMessageContaining("invalid data");
        }
    }

    private ZipFile zip(Map<String, String> files) throws IOException {
        Path file = Files.createTempFile(dir, "feed", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return new ZipFile(file.toFile());
    }
}
