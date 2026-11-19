package com.transitanalytics.zbiorkom;

import com.transitanalytics.service.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BialystokFeedTest {

    private static final String BBOX = "22.921150,53.016270,23.382496,53.243687";
    private static final String URL = "https://zbiorkom.test/api/bialystok/positions/" + BBOX;
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private MockRestServiceServer server;
    private VehicleRegistry vehicles;
    private BialystokFeed feed;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        vehicles = mock(VehicleRegistry.class);
        var properties = new ZbiorkomProperties("https://zbiorkom.test/api", Duration.ofSeconds(1),
                Duration.ofSeconds(1), new ZbiorkomProperties.Bialystok(BBOX, null));
        feed = new BialystokFeed(ZbiorkomClients.withoutTimeouts(builder, properties, JsonMapper.builder().build()),
                properties, vehicles, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mapsPositionsAndRegistersTheirVehicles() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(new ClassPathResource("zbiorkom/bialystok-positions.json"), MediaType.APPLICATION_JSON));
        given(vehicles.idsFor(anyCollection())).willReturn(Map.of("230", 7L, "1051", 8L));

        BialystokFeed.Snapshot snapshot = feed.poll();

        assertThat(snapshot.fetchedAt()).isEqualTo(NOW);
        assertThat(snapshot.raw()).isNotEmpty();
        List<FeedPosition> positions = snapshot.positions();
        assertThat(positions).hasSize(2);
        FeedPosition city = positions.get(0);
        assertThat(city.vehicleId()).isEqualTo(7L);
        assertThat(city.vehicleNumber()).isEqualTo("230");
        assertThat(city.agency()).isEqualTo("BKM");
        assertThat(city.type()).isEqualTo("BUS");
        assertThat(city.model()).isEqualTo("Solaris Urbino 12 W33");
        assertThat(city.routeName()).isEqualTo("18");
        assertThat(city.latitude()).isEqualTo(53.152219);
        assertThat(city.longitude()).isEqualTo(23.07823);
        assertThat(city.timestamp()).isEqualTo(Instant.ofEpochMilli(1794819764000L));
        assertThat(city.upcomingStops()).hasSize(3);
        assertThat(city.upcomingStops().get(0).scheduledArrival()).isEqualTo(Instant.ofEpochMilli(1794819900000L));

        FeedPosition regional = positions.get(1);
        assertThat(regional.agency()).isEqualTo("WSCHODEXPRESS");
        assertThat(regional.delaySeconds()).isEqualTo(104L);

        verify(vehicles).idsFor(List.of(
                new VehicleRegistry.Discovered("230", "BUS", "BKM", "Solaris Urbino 12 W33"),
                new VehicleRegistry.Discovered("1051", "BUS", "WSCHODEXPRESS", "Solaris Urbino 8,9 LE")));
        server.verify();
    }

    @Test
    void fetchesTheResponseAsReceivedAndReadsSavedOnes() throws Exception {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(new ClassPathResource("zbiorkom/bialystok-positions.json"), MediaType.APPLICATION_JSON));
        byte[] raw = feed.fetchRaw();
        Path plain = Files.write(dir.resolve("a.json"), raw);
        Path gz = dir.resolve("b.json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(raw);
        }
        assertThat(BialystokFeed.read(plain)).isEqualTo(raw);
        assertThat(BialystokFeed.read(gz)).isEqualTo(raw);
        assertThatThrownBy(() -> BialystokFeed.read(dir.resolve("missing.json")))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(feed.positions(new byte[0])).isEmpty();
    }

    @Test
    void upstreamFailureBecomesExternalServiceException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(feed::poll).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void mapsGtfsTypesAndDefaultAgency() {
        assertThat(BialystokFeed.vehicleType("0")).isEqualTo("TRAM");
        assertThat(BialystokFeed.vehicleType("11")).isEqualTo("TROLLEYBUS");
        assertThat(BialystokFeed.vehicleType("700")).isEqualTo("700");
        assertThat(BialystokFeed.vehicleType(null)).isEqualTo("UNKNOWN");
        assertThat(BialystokFeed.agency("default")).isEqualTo("BKM");
        assertThat(BialystokFeed.agency(null)).isEqualTo("BKM");
        assertThat(BialystokFeed.agency("WSCHODEXPRESS")).isEqualTo("WSCHODEXPRESS");
    }

    @Test
    void leavesExcludedLinesOutOfPositionsAndTheArchivedResponse() {
        RestClient.Builder builder = RestClient.builder();
        var properties = new ZbiorkomProperties("https://zbiorkom.test/api", Duration.ofSeconds(1),
                Duration.ofSeconds(1), new ZbiorkomProperties.Bialystok(BBOX, "^C[0-9]+$"));
        BialystokFeed excluding = new BialystokFeed(
                ZbiorkomClients.withoutTimeouts(builder, properties, JsonMapper.builder().build()), properties,
                vehicles, Clock.fixed(NOW, ZoneOffset.UTC));
        given(vehicles.idsFor(anyCollection())).willReturn(Map.of("230", 7L, "231", 8L));
        byte[] raw = """
                {"positions":[
                 {"vehicle":{"id":"230","type":"3"},"routeName":"18","location":[23.1,53.1]},
                 {"vehicle":{"id":"231","type":"3"},"routeName":"C2","location":[23.2,53.2]}]}""".getBytes();

        assertThat(excluding.positions(raw)).extracting(FeedPosition::routeName).containsExactly("18");
        assertThat(excluding.positionsWithoutIds(raw)).extracting(FeedPosition::routeName).containsExactly("18");
        String archived = new String(excluding.withoutExcluded(raw));
        assertThat(archived).contains("\"18\"").doesNotContain("C2");
        // Nothing to leave out, or not readable: as received.
        byte[] plain = "{\"positions\":[]}".getBytes();
        assertThat(excluding.withoutExcluded(plain)).isSameAs(plain);
        byte[] broken = "not json".getBytes();
        assertThat(excluding.withoutExcluded(broken)).isSameAs(broken);
        assertThat(excluding.withoutExcluded(new byte[0])).isEmpty();
        assertThat(feed.withoutExcluded(raw)).as("nothing excluded").isSameAs(raw);
        assertThat(properties.bialystok().excluded("C10")).isTrue();
        assertThat(properties.bialystok().excluded("103")).isFalse();
        assertThat(properties.bialystok().excluded(null)).isFalse();
    }
}
