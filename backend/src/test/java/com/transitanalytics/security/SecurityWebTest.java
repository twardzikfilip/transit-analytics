package com.transitanalytics.security;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.gtfs.GtfsBialystokService;
import com.transitanalytics.gtfs.GtfsFeedException;
import com.transitanalytics.gtfs.GtfsImportInProgressException;
import com.transitanalytics.gtfs.GtfsImportResult;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.service.VehicleService;
import com.transitanalytics.web.GtfsController;
import com.transitanalytics.web.PositionHistoryController;
import com.transitanalytics.web.LineStatsController;
import com.transitanalytics.service.LineStatsService;
import com.transitanalytics.dto.LineStatsDto;
import com.transitanalytics.recording.PositionHistoryService;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.web.VehicleController;
import com.transitanalytics.web.ZbiorkomController;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, VehicleController.class, ZbiorkomController.class,
        GtfsController.class, PositionHistoryController.class,
        LineStatsController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, SecurityWebTest.Metrics.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.expiration-minutes=5",
        "app.cors.allowed-origins=http://localhost:4200"
})
class SecurityWebTest {

    private static final String VEHICLE_JSON = """
            {"vehicleNumber":"BUS-1","type":"BUS","capacity":50}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    @Autowired
    MeterRegistry meterRegistry;

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @MockitoBean
    AuthenticationManager authenticationManager;

    @MockitoBean
    VehicleService vehicleService;

    @MockitoBean
    ZbiorkomBialystokService zbiorkomBialystokService;

    @MockitoBean
    GtfsBialystokService gtfsBialystokService;

    @MockitoBean
    PositionHistoryService positionHistoryService;

    @MockitoBean
    LineStatsService lineStatsService;

    @Test
    void loginReturnsToken() throws Exception {
        given(authenticationManager.authenticate(any())).willReturn(
                new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(loginCount("success")).isEqualTo(1.0);
    }

    @Test
    void badCredentialsReturn401() throws Exception {
        given(authenticationManager.authenticate(any())).willThrow(new BadCredentialsException("bad"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(loginCount("failure")).isEqualTo(1.0);
    }

    @Test
    void meRequiresToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("guest"))
                .andExpect(jsonPath("$.role").value("GUEST"));
    }

    @Test
    void guestCannotCreateVehicle() throws Exception {
        mvc.perform(post("/api/vehicles").header("Authorization", bearer("guest", "GUEST"))
                        .contentType(MediaType.APPLICATION_JSON).content(VEHICLE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateVehicle() throws Exception {
        given(vehicleService.create(any())).willReturn(new VehicleDto.Response(1L, "BUS-1", "BUS", 50, null, null));

        mvc.perform(post("/api/vehicles").header("Authorization", bearer("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VEHICLE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void guestCanReadLivePositions() throws Exception {
        given(zbiorkomBialystokService.positions()).willReturn(List.of(new LivePositionDto(7L, "230", "BUS", "BKM",
                "Solaris Urbino 12 W33", "18", "#006963", "018-02", "Warzywna", 53.15, 23.08, 152.0, 0L,
                Instant.parse("2026-11-16T09:00:00Z"), List.of(), null)));

        mvc.perform(get("/api/zbiorkom/bialystok/positions").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleNumber").value("230"))
                .andExpect(jsonPath("$[0].agency").value("BKM"))
                .andExpect(jsonPath("$[0].timestamp").value("2026-11-16T09:00:00Z"));
    }

    @Test
    void livePositionsRequireToken() throws Exception {
        mvc.perform(get("/api/zbiorkom/bialystok/positions")).andExpect(status().isUnauthorized());
    }

    @Test
    void upstreamFailureReturns502() throws Exception {
        given(zbiorkomBialystokService.positions())
                .willThrow(new ExternalServiceException("zbiorkom.live", new IOException("timeout")));

        mvc.perform(get("/api/zbiorkom/bialystok/positions").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Live data source unavailable"));
    }

    @Test
    void guestCannotImportGtfs() throws Exception {
        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanImportGtfs() throws Exception {
        given(gtfsBialystokService.importFeed()).willReturn(new GtfsImportResult("bialystok", "https://x", 10,
                Instant.parse("2026-11-16T10:00:00Z"), 1234, java.util.Map.of("stop", 1083L), java.util.Map.of(),
                List.of()));

        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.stop").value(1083));
    }

    @Test
    void gtfsImportErrorsMapToStatusCodes() throws Exception {
        given(gtfsBialystokService.importFeed())
                .willThrow(new GtfsImportInProgressException("bialystok"))
                .willThrow(new GtfsFeedException("The feed has no stops.txt"));

        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The feed has no stops.txt"));
    }

    @Test
    void guestCanReadPositionHistory() throws Exception {
        Instant from = Instant.parse("2026-11-16T10:00:00Z");
        Instant to = Instant.parse("2026-11-16T11:00:00Z");
        given(positionHistoryService.fleet(from, to, "103")).willReturn(new PositionHistoryDto.FleetSeries(from, to, 60,
                "103", List.of(new PositionHistoryDto.FleetPoint(from, 7.0, 7, 7, 30.0)),
                new PositionHistoryDto.Summary(7, 8, 7, 7, from, 30.0, from, from)));
        given(positionHistoryService.delays(from, to, null)).willReturn(List.of(
                new PositionHistoryDto.DelayBand(null, 1, 5, 40), new PositionHistoryDto.DelayBand(10, null, 1, 2)));

        mvc.perform(get("/api/positions/fleet").param("from", from.toString()).param("to", to.toString())
                        .param("route", "103").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].vehicles").value(7))
                .andExpect(jsonPath("$.stepSeconds").value(60))
                .andExpect(jsonPath("$.summary.peakVehicles").value(7));
        mvc.perform(get("/api/positions/delays").param("from", from.toString()).param("to", to.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toMinutes").value(1))
                .andExpect(jsonPath("$[1].fromMinutes").value(10));
        mvc.perform(get("/api/positions/fleet").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void positionHistoryRejectsBadWindows() throws Exception {
        given(positionHistoryService.fleet(any(), any(), any()))
                .willThrow(new IllegalArgumentException("from must be before to"));

        mvc.perform(get("/api/positions/fleet").param("from", "2026-11-16T11:00:00Z").param("to", "2026-11-16T10:00:00Z")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must be before to"));
        mvc.perform(get("/api/positions/fleet").param("from", "yesterday").param("to", "2026-11-16T10:00:00Z")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/positions/fleet").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void guestCanReadLineStats() throws Exception {
        given(lineStatsService.weekdayReport()).willReturn(new LineStatsDto.Report("P_1349", 59, null, null, List.of(),
                List.of(new LineStatsDto.Period("17:00", "20:00")),
                List.of(new LineStatsDto.Line(17L, "103", null, null, 19.8, 2400, 3000, 24.5, 26.0, 31.7, 40.2, 0.39, 0.2, 5, 4,
                        64))));

        mvc.perform(get("/api/lines/stats").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lengthKm").value(19.8))
                .andExpect(jsonPath("$.offPeak[0].to").value("20:00"));
        mvc.perform(get("/api/lines/stats")).andExpect(status().isUnauthorized());
    }

    private double loginCount(String outcome) {
        var counter = meterRegistry.find(AuthController.LOGINS_METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private String bearer(String user, String role) {
        return "Bearer " + jwtService.generateToken(user, role);
    }
}
