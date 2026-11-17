package com.transitanalytics.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Recently computed values, each kept for a while: reports asked for again within that time (e.g. on a reload) are
 * served from here. Only the most recently used few are kept.
 *
 * @param <K> what the value was computed for, e.g. a time window
 */
final class RecentCache<K, V> {

    private record Entry<V>(Instant computed, V value) {
    }

    private final Clock clock;
    private final Duration keepFor;
    private final Map<K, Entry<V>> entries;

    RecentCache(Clock clock, Duration keepFor, int size) {
        this.clock = clock;
        this.keepFor = keepFor;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > size;
            }
        };
    }

    /** The value kept for the key, else a freshly computed one (computed outside the lock). */
    V get(K key, Supplier<V> compute) {
        Instant now = clock.instant();
        synchronized (entries) {
            Entry<V> entry = entries.get(key);
            if (entry != null && entry.computed().plus(keepFor).isAfter(now)) {
                return entry.value();
            }
        }
        V value = compute.get();
        synchronized (entries) {
            entries.put(key, new Entry<>(now, value));
        }
        return value;
    }
}
