package com.transitanalytics.security;

import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.VehicleService;
import com.transitanalytics.web.VehicleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, VehicleController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.expiration-minutes=5",
        "app.cors.allowed-origins=http://localhost:4200"
})
class SecurityWebTest {

    private static final String VEHICLE_JSON = """
            {"vehicleNumber":"BUS-1","type":"BUS","capacity":50,"active":true}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    @MockitoBean
    AuthenticationManager authenticationManager;

    @MockitoBean
    VehicleService vehicleService;

    @Test
    void loginReturnsToken() throws Exception {
        given(authenticationManager.authenticate(any())).willReturn(
                new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void badCredentialsReturn401() throws Exception {
        given(authenticationManager.authenticate(any())).willThrow(new BadCredentialsException("bad"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
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
        given(vehicleService.create(any())).willReturn(new VehicleDto.Response(1L, "BUS-1", "BUS", 50, true));

        mvc.perform(post("/api/vehicles").header("Authorization", bearer("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VEHICLE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    private String bearer(String user, String role) {
        return "Bearer " + jwtService.generateToken(user, role);
    }
}
