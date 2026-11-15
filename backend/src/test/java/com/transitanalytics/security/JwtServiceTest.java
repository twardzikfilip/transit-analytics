package com.transitanalytics.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService(new JwtProperties("test-secret-test-secret-test-secret-123456", 5));

    @Test
    void generatedTokenRoundTrips() {
        String token = jwtService.generateToken("alice", "ADMIN");

        var claims = jwtService.parse(token).orElseThrow();
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get(JwtService.ROLE_CLAIM, String.class)).isEqualTo("ADMIN");
    }

    @Test
    void tokenSignedWithOtherKeyIsRejected() {
        var other = new JwtService(new JwtProperties("another-secret-another-secret-another-1234", 5));
        assertThat(jwtService.parse(other.generateToken("alice", "ADMIN"))).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    }
}
