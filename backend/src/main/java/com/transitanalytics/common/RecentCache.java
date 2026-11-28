package com.transitanalytics.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Recently computed values, each kept for a while: reports asked for again within that time (e.g. on a reload) are
 * served from here. Only the most recently used few are kept. A value being computed is computed once: whoever asks for
 * it meanwhile (e.g. a poll and a page opened at the same time) waits for it.
 *
 * @param <K> what the value was computed for, e.g. a time window
 */
public final class RecentCache<K, V> {

    private record Entry<V>(Instant computed, V value) {
    }

    private final Clock clock;
    private final Duration keepFor;
    private final Map<K, Entry<V>> entries;
    /** Values being computed, by key; guarded by {@code entries}. */
    private final Map<K, CompletableFuture<V>> computing = new HashMap<>();

    public RecentCache(Clock clock, Duration keepFor, int size) {
        this.clock = clock;
        this.keepFor = keepFor;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > size;
            }
        };
    }

    /** The value kept for the key, else a freshly computed one (computed outside the lock, once at a time per key). */
    public V get(K key, Supplier<V> compute) {
        Instant now = clock.instant();
        CompletableFuture<V> mine;
        CompletableFuture<V> theirs;
        synchronized (entries) {
            Entry<V> entry = entries.get(key);
            if (entry != null && entry.computed().plus(keepFor).isAfter(now)) {
                return entry.value();
            }
            theirs = computing.get(key);
            mine = theirs == null ? new CompletableFuture<>() : null;
            if (mine != null) {
                computing.put(key, mine);
            }
        }
        if (theirs != null) {
            return await(theirs);
        }
        try {
            V value = compute.get();
            synchronized (entries) {
                entries.put(key, new Entry<>(now, value));
                computing.remove(key);
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            // A failure isn't kept: the next request computes again.
            synchronized (entries) {
                computing.remove(key);
            }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** Forgets every kept value (values being computed are still handed to whoever waits for them). */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static <V> V await(CompletableFuture<V> value) {
        try {
            return value.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
