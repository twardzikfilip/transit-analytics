package com.transitanalytics.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RecentCacheTest {

    /** A clock the test moves on. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");

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
    }

    @Test
    void keepsValuesForAWhileAndOnlyTheMostRecentlyUsed() {
        TestClock clock = new TestClock();
        RecentCache<String, Integer> cache = new RecentCache<>(clock, Duration.ofMinutes(2), 2);
        AtomicInteger computed = new AtomicInteger();

        assertThat(cache.get("a", computed::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("a", computed::incrementAndGet)).as("kept").isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(cache.get("a", computed::incrementAndGet)).as("expired").isEqualTo(2);

        cache.get("b", computed::incrementAndGet);
        cache.get("c", computed::incrementAndGet);
        assertThat(cache.get("a", computed::incrementAndGet)).as("dropped: the least recently used").isEqualTo(5);
        assertThat(cache.get("c", computed::incrementAndGet)).isEqualTo(4);
    }
}
