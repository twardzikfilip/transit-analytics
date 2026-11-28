package com.transitanalytics.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void computesAValueOnceWhenAskedForItTwiceAtTheSameTime() throws Exception {
        RecentCache<String, Integer> cache = new RecentCache<>(new TestClock(), Duration.ofMinutes(2), 2);
        AtomicInteger computed = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> cache.get("a", () -> {
                started.countDown();
                await(release);
                return computed.incrementAndGet();
            }));
            started.await(5, TimeUnit.SECONDS);
            Future<Integer> second = pool.submit(() -> cache.get("a", computed::incrementAndGet));
            Thread.sleep(50);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS)).as("waited for the first").isEqualTo(1);
            assertThat(computed).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doesNotKeepAFailure() {
        RecentCache<String, Integer> cache = new RecentCache<>(new TestClock(), Duration.ofMinutes(2), 2);

        assertThatThrownBy(() -> cache.get("a", () -> {
            throw new IllegalStateException("database down");
        })).hasMessage("database down");
        assertThat(cache.get("a", () -> 7)).as("computed again").isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
