package com.transitanalytics.recording;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.LivePositionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Position recording against the real TimescaleDB database. Uses vehicle numbers starting with "IT-" and removes
 * them (and their positions) afterwards. Run with INTEGRATION_TESTS=true and the DB up.
 */
@DbIntegrationTest
class RecordingIntegrationTest {

    @TempDir
    static Path archiveDir;

    @DynamicPropertySource
    static void archive(DynamicPropertyRegistry registry) {
        registry.add("app.recording.archive-dir", archiveDir::toString);
    }

    /** Backfill data: far from real data, cleaned up (including aggregates) after each test. */
    private static final Instant T1 = Instant.parse("2020-01-01T10:00:12Z");
    private static final Instant T2 = Instant.parse("2020-01-01T10:01:15Z");
    /**
     * Rolled-back data: real-time aggregation only adds raw rows newer than what has been materialised, so use a
     * time that is always newer.
     */
    private static final Instant F1 = Instant.parse("2099-01-01T10:00:12Z");
    private static final Instant F2 = Instant.parse("2099-01-01T10:01:15Z");

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    SnapshotImportRunner importer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @TempDir
    Path snapshots;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM position_sample WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE vehicle_number LIKE 'IT-%')");
        jdbc.update("DELETE FROM vehicle WHERE vehicle_number LIKE 'IT-%'");
        for (String view : List.of("fleet_5min", "route_delay_hourly")) {
            jdbc.execute("CALL refresh_continuous_aggregate('" + view + "', '2019-12-31', '2020-01-02')");
        }
        jdbc.execute("SELECT drop_chunks('position_sample', older_than => TIMESTAMPTZ '2021-01-01')");
    }

    @Test
    void writerStoresEachReportOnceAndAggregatesSeeItImmediately() {
        transaction.executeWithoutResult(status -> {
            Long vehicle = jdbc.queryForObject(
                    "INSERT INTO vehicle (vehicle_number, type) VALUES ('IT-W1', 'BUS') RETURNING id", Long.class);
            List<LivePositionDto> snapshot = List.of(
                    position(vehicle, F1, 30L, "IT-ROUTE"), position(vehicle, F2, 90L, "IT-ROUTE"));

            assertThat(writer.write(snapshot, F2)).isEqualTo(2);
            assertThat(writer.write(snapshot, F2)).as("same reports again").isZero();

            // Real-time aggregation includes rows that aren't materialised yet.
            Double mean = jdbc.queryForObject("SELECT average(delay_stats) FROM route_delay_hourly "
                    + "WHERE route_name = 'IT-ROUTE' AND bucket = '2099-01-01T10:00:00Z'", Double.class);
            assertThat(mean).isEqualTo(60.0);
            Long vehicles = jdbc.queryForObject("SELECT sum(vehicles) FROM fleet_5min "
                    + "WHERE bucket = '2099-01-01T10:00:00Z'", Long.class);
            assertThat(vehicles).isEqualTo(1L);
            status.setRollbackOnly();
        });
    }

    @Test
    void backfillImportsJsonAndGzipFilesArchivesThemAndIsRepeatable() throws IOException {
        Files.writeString(snapshots.resolve("a.json"), feed("IT-I1", T1), StandardCharsets.UTF_8);
        Path nested = Files.createDirectories(snapshots.resolve("day2"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(nested.resolve("b.json.gz")))) {
            out.write(feed("IT-I1", T2).getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(snapshots.resolve("broken.json"), "{not json", StandardCharsets.UTF_8);

        SnapshotImportRunner.Result first = importer.importFrom(snapshots);
        assertThat(first.files()).isEqualTo(3);
        assertThat(first.failedFiles()).isEqualTo(1);
        assertThat(first.stored()).isEqualTo(2);
        assertThat(first.first()).isEqualTo(T1);
        assertThat(first.last()).isEqualTo(T2);

        SnapshotImportRunner.Result again = importer.importFrom(snapshots);
        assertThat(again.stored()).as("re-import adds nothing").isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id "
                + "WHERE v.vehicle_number = 'IT-I1'", Long.class)).isEqualTo(2L);
        assertThat(archiveDir.resolve("zbiorkom-bialystok/2020/01/01/20200101T100012Z.json.gz")).exists();
        assertThat(archiveDir.resolve("zbiorkom-bialystok/2020/01/01/20200101T100115Z.json.gz")).exists();

        // The backfill refreshed the aggregates for its period.
        Double mean = jdbc.queryForObject("SELECT average(delay_stats) FROM route_delay_hourly "
                + "WHERE route_name = 'IT-ROUTE' AND bucket = '2020-01-01T10:00:00Z'", Double.class);
        assertThat(mean).isEqualTo(60.0);
    }

    private static LivePositionDto position(Long vehicleId, Instant at, Long delay, String route) {
        return new LivePositionDto(vehicleId, "IT-W1", "BUS", "BKM", null, route, null, "IT-01", "Test", 53.13, 23.16,
                90.0, delay, at, List.of(new LivePositionDto.UpcomingStop(3, "Next stop", at, at)));
    }

    /** One zbiorkom response with a single vehicle. */
    private static String feed(String vehicle, Instant at) {
        return """
                {"positions":[{"vehicle":{"id":"%s","type":"3","agency":"default","model":"Test bus"},
                "routeName":"IT-ROUTE","routeColor":"#000000","brigade":"IT-01","location":[23.16,53.13],
                "timestamp":%d,"bearing":90,"delay":60000,"headsign":"Test",
                "upcomingStops":[{"sequence":1,"name":"Next","scheduledArrival":%d,"scheduledDeparture":%d}]}]}
                """.formatted(vehicle, at.toEpochMilli(), at.toEpochMilli(), at.toEpochMilli());
    }
}
