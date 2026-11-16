package com.transitanalytics.zbiorkom;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ZbiorkomBialystokServiceTest {

    private static final String BBOX = "22.921150,53.016270,23.382496,53.243687";
    private static final String URL = "https://zbiorkom.test/api/bialystok/positions/" + BBOX;

    private MockRestServiceServer server;
    private VehicleService vehicleService;
    private ZbiorkomBialystokService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://zbiorkom.test/api");
        server = MockRestServiceServer.bindTo(builder).build();
        vehicleService = mock(VehicleService.class);
        var properties = new ZbiorkomProperties("https://zbiorkom.test/api", Duration.ofSeconds(1),
                Duration.ofSeconds(1), new ZbiorkomProperties.Bialystok(BBOX));
        service = new ZbiorkomBialystokService(new ZbiorkomClient(builder.build()), properties, vehicleService);
    }

    @Test
    void mapsPositionsAndRegistersTheirVehicles() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(new ClassPathResource("zbiorkom/bialystok-positions.json"), MediaType.APPLICATION_JSON));
        given(vehicleService.registerMissing(anyCollection())).willReturn(Map.of("230", 7L, "1051", 8L));

        List<LivePositionDto> positions = service.positions();

        assertThat(positions).hasSize(2);
        LivePositionDto city = positions.get(0);
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

        LivePositionDto regional = positions.get(1);
        assertThat(regional.agency()).isEqualTo("WSCHODEXPRESS");
        assertThat(regional.delaySeconds()).isEqualTo(104L);

        verify(vehicleService).registerMissing(List.of(
                new VehicleDto.Discovered("230", "BUS", "BKM", "Solaris Urbino 12 W33"),
                new VehicleDto.Discovered("1051", "BUS", "WSCHODEXPRESS", "Solaris Urbino 8,9 LE")));
        server.verify();
    }

    @Test
    void stillReturnsPositionsWhenARegistrationRaceFails() {
        server.expect(requestTo(URL)).andRespond(
                withSuccess(new ClassPathResource("zbiorkom/bialystok-positions.json"), MediaType.APPLICATION_JSON));
        given(vehicleService.registerMissing(anyCollection())).willThrow(new DataIntegrityViolationException("dup"));

        assertThat(service.positions()).hasSize(2).allSatisfy(p -> assertThat(p.vehicleId()).isNull());
    }

    @Test
    void upstreamFailureBecomesExternalServiceException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(service::positions).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void mapsGtfsTypesAndDefaultAgency() {
        assertThat(ZbiorkomBialystokService.vehicleType("0")).isEqualTo("TRAM");
        assertThat(ZbiorkomBialystokService.vehicleType("11")).isEqualTo("TROLLEYBUS");
        assertThat(ZbiorkomBialystokService.vehicleType("700")).isEqualTo("700");
        assertThat(ZbiorkomBialystokService.agency("default")).isEqualTo("BKM");
        assertThat(ZbiorkomBialystokService.agency("WSCHODEXPRESS")).isEqualTo("WSCHODEXPRESS");
    }
}
