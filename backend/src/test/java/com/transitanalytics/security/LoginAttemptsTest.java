package com.transitanalytics.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptsTest {

    private Instant now = Instant.parse("2026-11-24T12:00:00Z");
    private final LoginAttempts attempts = new LoginAttempts(new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private void fail(String address, int times) {
        for (int i = 0; i < times; i++) {
            attempts.attempt(address);
        }
    }

    @Test
    void anAddressWaitsAfterTooManyFailuresOthersDont() {
        fail("203.0.113.7", LoginAttempts.MAX_FAILURES);
        now = now.plus(Duration.ofMinutes(5));

        assertThatThrownBy(() -> attempts.attempt("203.0.113.7")).isInstanceOf(TooManyLoginsException.class)
                .hasMessageContaining("10 min");
        assertThatCode(() -> attempts.attempt("203.0.113.8")).doesNotThrowAnyException();
        now = now.plus(Duration.ofMinutes(10));
        assertThatCode(() -> attempts.attempt("203.0.113.7")).as("the window is over").doesNotThrowAnyException();
    }

    @Test
    void successesDontCountButDontResetFailuresEither() {
        for (int i = 0; i < 50; i++) {
            attempts.attempt("203.0.113.7");
            attempts.succeeded("203.0.113.7");
        }
        fail("203.0.113.7", LoginAttempts.MAX_FAILURES - 1);
        attempts.attempt("203.0.113.7");
        attempts.succeeded("203.0.113.7");
        attempts.attempt("203.0.113.7");
        assertThatThrownBy(() -> attempts.attempt("203.0.113.7")).isInstanceOf(TooManyLoginsException.class);
    }

    @Test
    void anIpv6NetworkIsOneAddress() {
        assertThat(LoginAttempts.key("2001:db8:1:2::1")).isEqualTo(LoginAttempts.key("2001:db8:1:2:ffff::9"))
                .isNotEqualTo(LoginAttempts.key("2001:db8:1:3::1"));
        assertThat(LoginAttempts.key("not:an:address")).isEqualTo("not:an:address");
        assertThat(LoginAttempts.key(null)).isEqualTo("null");
    }

    @Test
    void keepsALimitedNumberOfAddresses() {
        fail("198.51.100.1", LoginAttempts.MAX_FAILURES);
        now = now.plusSeconds(1);
        for (int i = 0; i < LoginAttempts.MAX_ADDRESSES; i++) {
            attempts.attempt("a" + i);
        }
        assertThatCode(() -> attempts.attempt("198.51.100.1")).as("the oldest went").doesNotThrowAnyException();
    }
}
