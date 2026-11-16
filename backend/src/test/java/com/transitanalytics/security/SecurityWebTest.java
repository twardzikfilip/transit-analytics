package com.transitanalytics.security;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.service.VehicleService;
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

@WebMvcTest(controllers = {AuthController.class, VehicleController.class, ZbiorkomController.class})
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
                Instant.parse("2026-11-16T09:00:00Z"), List.of())));

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

    private double loginCount(String outcome) {
        var counter = meterRegistry.find(AuthController.LOGINS_METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private String bearer(String user, String role) {
        return "Bearer " + jwtService.generateToken(user, role);
    }
}
