package com.transitanalytics.security;

import com.transitanalytics.dto.AuthDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String LOGINS_METRIC = "transit.auth.logins";

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final Counter loginFailures;
    private final MeterRegistry meterRegistry;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          MeterRegistry meterRegistry) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.meterRegistry = meterRegistry;
        this.loginFailures = Counter.builder(LOGINS_METRIC)
                .description("Login attempts")
                .tag("outcome", "failure")
                .tag("role", "none")
                .register(meterRegistry);
    }

    @PostMapping("/login")
    public AuthDto.LoginResponse login(@Valid @RequestBody AuthDto.LoginRequest request) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            loginFailures.increment();
            throw e;
        }
        String role = roleOf(auth);
        // Tag by role only (low cardinality); never by username.
        meterRegistry.counter(LOGINS_METRIC, "outcome", "success", "role", role).increment();
        return new AuthDto.LoginResponse(jwtService.generateToken(auth.getName(), role), auth.getName(), role);
    }

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
