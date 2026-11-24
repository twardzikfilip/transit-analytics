package com.transitanalytics.security;

import com.transitanalytics.dto.AuthDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentication", description = "Log in and inspect the current user")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String LOGINS_METRIC = "transit.auth.logins";

    private final AuthenticationManager authenticationManager;
    private final LoginAttempts attempts;
    private final JwtService jwtService;
    private final Counter loginFailures;
    private final MeterRegistry meterRegistry;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          LoginAttempts attempts, MeterRegistry meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.attempts = attempts;
        this.meterRegistry = meterRegistry;
        this.loginFailures = Counter.builder(LOGINS_METRIC)
                .description("Login attempts")
                .tag("outcome", "failure")
                .tag("role", "none")
                .register(meterRegistry);
    }

    @Operation(summary = "Log in", description = "Returns a JWT. In Swagger UI, paste it into Authorize to call the other endpoints.")
    @SecurityRequirements // no token needed for this one
    @PostMapping("/login")
    public AuthDto.LoginResponse login(@Valid @RequestBody AuthDto.LoginRequest request,
                                       HttpServletRequest http) {
        attempts.attempt(http.getRemoteAddr());
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            loginFailures.increment();
            throw e;
        }
        attempts.succeeded(http.getRemoteAddr());
        String role = roleOf(auth);
        // Tag by role only (low cardinality); never by username.
        meterRegistry.counter(LOGINS_METRIC, "outcome", "success", "role", role).increment();
        return new AuthDto.LoginResponse(jwtService.generateToken(auth.getName(), role), auth.getName(), role);
    }

    @Operation(summary = "Current user and role")
    @GetMapping("/me")
    public AuthDto.Me me(Authentication auth) {
        return new AuthDto.Me(auth.getName(), roleOf(auth));
    }

    private static String roleOf(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElseThrow();
    }
}
