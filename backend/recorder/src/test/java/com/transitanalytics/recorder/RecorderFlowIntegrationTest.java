package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.recording.RawArchive;
import com.transitanalytics.recording.RecordingWindow;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.VehicleRegistry;
import com.transitanalytics.zbiorkom.ZbiorkomClients;
import com.transitanalytics.zbiorkom.ZbiorkomProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The recorder's whole path against the real database, logged in as its own role ({@code transit_recorder}, V16):
 * poll, archive, store; a database that is down; the spool storing what was missed. Vehicles "IT-RF…", reports in
 * 2099; removed afterwards.
 */
@RecorderDbTest
class RecorderFlowIntegrationTest {

    private static final String BBOX = "22.92,53.01,23.38,53.24";
    private static final Instant T1 = Instant.parse("2099-02-01T10:00:00Z"); // 11:00 in Warsaw: recording hours

    @Autowired
    JdbcTemplate owner;

    @Value("${spring.datasource.url}")
    String url;

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T1);

    @AfterEach
    void cleanUp() {
        owner.update("DELETE FROM position_sample WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE vehicle_number LIKE 'IT-RF%') AND observed_at >= '2099-01-01'");
        owner.update("DELETE FROM vehicle WHERE vehicle_number LIKE 'IT-RF%'");
    }

    private JdbcTemplate as(String user, String password, String jdbcUrl) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, user, password));
    }

    private static String response(String vehicle, Instant at) {
        return """
                {"positions":[{"vehicle":{"id":"%s","type":"3","agency":"default","model":"Test bus"},
                "routeName":"IT-RF","brigade":"IT-RF-01","location":[23.16,53.13],"timestamp":%d,"bearing":90,
                "delay":60000,"headsign":"Test","upcomingStops":[]}]}""".formatted(vehicle, at.toEpochMilli());
    }

    private record Parts(BialystokFeed feed, PositionSampleWriter writer) {
    }

    private Parts parts(JdbcTemplate jdbc, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), requestTo("https://zbiorkom.test/bialystok/positions/" + BBOX))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var properties = new ZbiorkomProperties("https://zbiorkom.test", Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ZbiorkomProperties.Bialystok(BBOX));
        return new Parts(new BialystokFeed(ZbiorkomClients.withoutTimeouts(builder, properties, JsonMapper.builder().build()),
                properties, new VehicleRegistry(jdbc), clock), new PositionSampleWriter(jdbc));
    }

    private PositionRecorder recorder(Parts parts, Spool spool) {
        RecordingProperties properties = PositionRecorderTest.properties(true, null);
        return new PositionRecorder(parts.feed(), parts.writer(), new RawArchive(dir.resolve("raw")), spool,
                new RecordingWindow(properties.windowStart(), properties.windowEnd(), properties.zone()), properties,
                clock, new SimpleMeterRegistry());
    }

    private Spool spool(Parts parts) {
        RecordingProperties p = PositionRecorderTest.properties(true, null);
        return new Spool(new RecordingProperties(true, p.cron(), p.zone(), p.windowStart(), p.windowEnd(),
                p.minInterval(), "unused", dir.resolve("spool").toString(), null), parts.feed(), parts.writer(),
                new SimpleMeterRegistry());
    }

    private long rows(String vehicle) {
        return owner.queryForObject("SELECT count(*) FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id "
                + "WHERE v.vehicle_number = ?", Long.class, vehicle);
    }

    @Test
    void recordsAsItsOwnRoleAndCatchesUpAfterTheDatabaseWasDown() {
        JdbcTemplate recorderRole = as("transit_recorder", "transit_recorder", url);
        Parts up = parts(recorderRole, response("IT-RF1", T1));
        PositionRecorder recorder = recorder(up, spool(up));

        assertThat(recorder.pollIfDue()).isTrue();
        assertThat(rows("IT-RF1")).isEqualTo(1);
        assertThat(dir.resolve("raw/zbiorkom-bialystok/2099/02/01/20990201T100000Z.json.gz")).exists();

        // The database "goes down" (nothing listens there): the next poll is archived and spooled.
        String down = url.replaceFirst("//[^/]+/", "//localhost:1/");
        Parts broken = parts(as("transit_recorder", "transit_recorder", down), response("IT-RF2", T1.plusSeconds(10)));
        Spool spool = spool(broken);
        PositionRecorder cut = recorder(broken, spool);
        clock.advance(Duration.ofSeconds(10));
        assertThat(cut.pollIfDue()).isTrue();
        assertThat(spool.size()).isEqualTo(1);
        assertThat(spool.replay()).as("still down").isZero();
        assertThat(rows("IT-RF2")).isZero();

        // Back up: the spool stores it.
        Spool recovered = spool(parts(recorderRole, "{}"));
        assertThat(recovered.replay()).isEqualTo(1);
        assertThat(rows("IT-RF2")).isEqualTo(1);
        assertThat(recovered.size()).isZero();
    }

    @Test
    void twoWritersRegisteringTheSameNewBusBothGetItsId() throws Exception {
        VehicleRegistry one = new VehicleRegistry(as("transit_recorder", "transit_recorder", url));
        VehicleRegistry two = new VehicleRegistry(owner);
        var bus = new VehicleRegistry.Discovered("IT-RF3", "BUS", "BKM", "Solaris Urbino 12");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Map<String, Long>>> both = pool.invokeAll(List.<Callable<Map<String, Long>>>of(
                    () -> one.idsFor(List.of(bus)), () -> two.idsFor(List.of(bus, bus))));
            Long id = both.get(0).get().get("IT-RF3");
            assertThat(id).isNotNull();
            assertThat(both.get(1).get().get("IT-RF3")).isEqualTo(id);
        } finally {
            pool.shutdown();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM vehicle WHERE vehicle_number = 'IT-RF3'", Long.class))
                .isEqualTo(1);
        assertThat(one.idsFor(List.of())).isEmpty();
    }
}
