package com.transitanalytics.gtfs;

import com.transitanalytics.DbIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
@DbIntegrationTest
@Transactional
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
        // Rows other feeds loaded (e.g. PKS Nova's route 500) stay: the counts are the whole tables. Not their stop
        // times at the city's stops: those stops go with this feed replacing the city's, and their stop times too.
        Map<String, Long> others = Map.of(
                "agency", count("SELECT count(*) FROM agency WHERE feed <> 'bialystok'"),
                "route", count("SELECT count(*) FROM route WHERE feed <> 'bialystok'"),
                "stop", count("SELECT count(*) FROM stop WHERE feed <> 'bialystok'"),
                "trip", count("SELECT count(*) FROM trip WHERE feed <> 'bialystok'"),
                "stop_time", count("SELECT count(*) FROM stop_time st JOIN trip t ON t.id = st.trip_id "
                        + "JOIN stop s ON s.id = st.stop_id WHERE t.feed <> 'bialystok' AND s.feed <> 'bialystok'"),
                "calendar_date", count("SELECT count(*) FROM calendar_date WHERE feed <> 'bialystok'"),
                "shape_point", count("SELECT count(*) FROM shape_point WHERE feed <> 'bialystok'"));
        GtfsImporter.Loaded first;
        try (ZipFile zip = zip(FEED)) {
            first = importer.load(zip);
        }

        assertThat(first.rows()).containsEntry("agency", 1 + others.get("agency"))
                .containsEntry("route", 2 + others.get("route")).containsEntry("stop", 2 + others.get("stop"))
                .containsEntry("trip", 1 + others.get("trip")).containsEntry("stop_time", 2 + others.get("stop_time"))
                .containsEntry("calendar_date", 1 + others.get("calendar_date"))
                .containsEntry("shape_point", others.get("shape_point"));
        // T2 has an unknown route; its stop time and the one at unknown stop S9 are skipped too.
        assertThat(first.skipped()).containsEntry("trips.txt", 1L).containsEntry("stop_times.txt", 2L);
        assertThat(first.ignoredFiles()).containsExactly("feed_info.txt");

        assertThat(jdbc.queryForObject("SELECT a.gtfs_id FROM route r JOIN agency a ON a.id = r.agency_id "
                + "WHERE r.feed = 'bialystok' AND r.gtfs_id = 'R1'", String.class))
                .as("route without agency_id gets the only agency")
                .isEqualTo("BKM");
        assertThat(jdbc.queryForObject("SELECT color FROM route WHERE feed = 'bialystok' AND gtfs_id = 'R1'",
                String.class))
                .isEqualTo("#FF0000");
        assertThat(jdbc.queryForObject("SELECT name FROM stop WHERE feed = 'bialystok' AND gtfs_id = 'S1'",
                String.class))
                .isEqualTo("Lipowa, Rynek");
        assertThat(jdbc.queryForList("SELECT st.departure_time FROM stop_time st JOIN trip t ON t.id = st.trip_id "
                + "WHERE t.feed = 'bialystok' AND t.gtfs_id = 'T1' ORDER BY st.stop_sequence", String.class))
                .containsExactly("04:49:00", "24:05:00");

        Long tripId = jdbc.queryForObject("SELECT id FROM trip WHERE feed = 'bialystok' AND gtfs_id = 'T1'",
                Long.class);
        try (ZipFile zip = zip(FEED)) {
            importer.load(zip);
        }
        assertThat(jdbc.queryForObject("SELECT id FROM trip WHERE feed = 'bialystok' AND gtfs_id = 'T1'", Long.class))
                .as("re-import keeps database ids").isEqualTo(tripId);
    }

    /**
     * Another operator's feed, with ids that clash with the main one's (agency, route, stop, trip, service, shape).
     * Its stop S3 is the main feed's S2 (its code, at the same place); S1 and S4 aren't (S4's code is S1's id, but it
     * is 1.3 km from it).
     */
    private static final Map<String, String> OTHER = Map.of(
            "agency.txt", """
                    agency_id,agency_name,agency_url,agency_timezone
                    BKM,Other operator,https://other.example,Europe/Warsaw
                    """,
            "routes.txt", """
                    route_id,agency_id,route_short_name,route_type
                    R1,BKM,500,3
                    """,
            "stops.txt", """
                    stop_id,stop_code,stop_name,stop_lat,stop_lon,parent_station
                    S1,,Dworzec autobusowy,53.13,23.13,
                    S2,,Supraśl,53.21,23.33,S1
                    S3,S2,Dworzec (city stop),53.1402,23.1603,
                    S4,S1,Far from S1,53.13,23.17,
                    """,
            "trips.txt", """
                    route_id,service_id,trip_id,trip_headsign,shape_id
                    R1,P,T1,Supraśl,SH
                    """,
            "stop_times.txt", """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence
                    T1,06:00:00,06:00:00,S1,0
                    T1,06:10:00,06:10:00,S3,1
                    T1,06:20:00,06:20:00,S4,2
                    T1,06:30:00,06:30:00,S2,3
                    """,
            "calendar_dates.txt", """
                    service_id,date,exception_type
                    P,20261117,1
                    """,
            "shapes.txt", """
                    shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
                    SH,53.13,23.13,0
                    SH,53.21,23.33,1
                    """);

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** Where a feed's trip stops, in order: each stop's feed and id. */
    private java.util.List<String> stopsOf(String feed, String trip) {
        return jdbc.queryForList("SELECT s.feed || ' ' || s.gtfs_id FROM stop_time st JOIN trip t ON t.id = st.trip_id"
                + " JOIN stop s ON s.id = st.stop_id WHERE t.feed = ? AND t.gtfs_id = ? ORDER BY st.stop_sequence",
                String.class, feed, trip);
    }

    @Test
    void keepsEachFeedsRowsApartAndReplacesOnlyItsOwn() throws IOException {
        try (ZipFile zip = zip(FEED)) {
            importer.load(zip);
        }
        try (ZipFile zip = zip(OTHER)) {
            importer.load(zip, "nova");
        }
        // The same ids in both feeds: each feed's rows are its own, with ids as the feed gives them.
        assertThat(jdbc.queryForObject("SELECT a.name FROM route r JOIN agency a ON a.id = r.agency_id "
                + "WHERE r.feed = 'nova' AND r.gtfs_id = 'R1'", String.class)).isEqualTo("Other operator");
        assertThat(jdbc.queryForObject("SELECT name FROM agency WHERE feed = 'bialystok' AND gtfs_id = 'BKM'",
                String.class)).isEqualTo("Test agency");
        assertThat(jdbc.queryForObject("SELECT parent_station FROM stop WHERE feed = 'nova' AND gtfs_id = 'S2'",
                String.class)).isEqualTo("S1");
        assertThat(jdbc.queryForObject("SELECT service_id || ' ' || shape_id FROM trip "
                + "WHERE feed = 'nova' AND gtfs_id = 'T1'", String.class)).isEqualTo("P SH");
        // Each feed's trip stops at its own stops; S3 is the city's S2, so the other feed has no copy of it.
        assertThat(stopsOf("bialystok", "T1")).containsExactly("bialystok S1", "bialystok S2");
        assertThat(stopsOf("nova", "T1")).containsExactly("nova S1", "bialystok S2", "nova S4", "nova S2");
        assertThat(count("SELECT count(*) FROM stop WHERE feed = 'nova'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM calendar_date WHERE feed = 'nova' AND service_id = 'P'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM shape_point WHERE feed = 'nova' AND shape_id = 'SH'")).isEqualTo(2);

        // Re-importing the main feed leaves the other one's rows alone, and the other way round.
        try (ZipFile zip = zip(FEED)) {
            importer.load(zip);
        }
        assertThat(stopsOf("nova", "T1")).hasSize(4);
        assertThat(count("SELECT count(*) FROM shape_point WHERE feed = 'nova'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM route WHERE gtfs_id IN ('R1', 'R2')"
                + " AND feed IN ('bialystok', 'nova')")).isEqualTo(3);
        Map<String, String> fewer = new java.util.HashMap<>(OTHER);
        fewer.put("calendar_dates.txt", "service_id,date,exception_type\nP,20261118,1\n");
        try (ZipFile zip = zip(fewer)) {
            importer.load(zip, "nova");
        }
        assertThat(jdbc.queryForList("SELECT date::text FROM calendar_date"
                + " WHERE feed = 'bialystok' AND service_id = 'P'", String.class)).containsExactly("2026-11-16");
        assertThat(jdbc.queryForList("SELECT date::text FROM calendar_date WHERE feed = 'nova' AND service_id = 'P'",
                String.class)).containsExactly("2026-11-18");
        assertThat(stopsOf("bialystok", "T1")).as("the main feed's stop times stay").hasSize(2);

        try (ZipFile zip = zip(OTHER)) {
            assertThatThrownBy(() -> importer.load(zip, "bad key")).isInstanceOf(IllegalArgumentException.class);
        }
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

    @Test
    void loadsOnlyTheChosenAgenciesTripsCallingAtTheChosenStations() throws IOException {
        Map<String, String> country = Map.of(
                "agency.txt", """
                        agency_id,agency_name,agency_url,agency_timezone
                        PR,Regional,https://example.org,Europe/Warsaw
                        IC,Fast,https://example.org,Europe/Warsaw
                        """,
                "routes.txt", """
                        route_id,agency_id,route_short_name,route_type
                        PR_REG,PR,REG,2
                        PR_FAR,PR,FAR,2
                        IC_IC,IC,IC,2
                        """,
                "stops.txt", """
                        stop_id,stop_name,stop_lat,stop_lon,parent_station
                        HUB,Hub,53.13,23.13,
                        HUB-1,Hub,53.13,23.13,HUB
                        A,A,53.2,23.2,
                        FAR,Far,50.0,20.0,
                        """,
                "trips.txt", """
                        trip_id,route_id,service_id,shape_id
                        KEPT,PR_REG,S1,SH1
                        ELSEWHERE,PR_FAR,S2,SH2
                        FAST,IC_IC,S2,SH2
                        """,
                "stop_times.txt", """
                        trip_id,stop_sequence,stop_id,arrival_time,departure_time
                        KEPT,0,HUB-1,08:00:00,08:00:00
                        KEPT,1,A,08:10:00,08:10:00
                        ELSEWHERE,0,FAR,08:00:00,08:00:00
                        FAST,0,HUB-1,08:00:00,08:00:00
                        """,
                "calendar_dates.txt", "service_id,date,exception_type\nS1,20261126,1\nS2,20261126,1\n",
                "shapes.txt", "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\nSH1,0,53.13,23.13\nSH2,0,50,20\n");
        try (ZipFile zip = zip(country)) {
            var loaded = importer.load(zip, "it-rail", new GtfsProperties.Selection(java.util.List.of("PR"), java.util.List.of("HUB", "NONE")));
            assertThat(loaded.skipped()).isEmpty();
        }
        assertThat(ids("agency")).containsExactly("PR");
        assertThat(ids("route")).containsExactly("PR_REG");
        assertThat(ids("trip")).containsExactly("KEPT");
        assertThat(ids("stop")).containsExactly("A", "HUB", "HUB-1");
        assertThat(stopsOf("it-rail", "KEPT")).containsExactly("it-rail HUB-1", "it-rail A");
        assertThat(jdbc.queryForList("SELECT service_id FROM calendar_date WHERE feed = 'it-rail'", String.class))
                .containsOnly("S1");
        assertThat(jdbc.queryForList("SELECT shape_id FROM shape_point WHERE feed = 'it-rail'", String.class))
                .containsExactly("SH1");
    }

    private java.util.List<String> ids(String table) {
        return jdbc.queryForList("SELECT gtfs_id FROM " + table + " WHERE feed = 'it-rail' ORDER BY gtfs_id",
                String.class);
    }
}
