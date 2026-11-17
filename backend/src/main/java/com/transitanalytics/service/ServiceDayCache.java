package com.transitanalytics.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Rows computed from recorded positions, read one service day (03:00–03:00) at a time. Days that are over don't
 * change, so their rows are kept; only the days at a window's edges (cut by it, or still running) are read again.
 * Days older than {@link #KEEP_DAYS} are dropped (an "all time" window reads them again).
 *
 * @param <T> a row
 */
final class ServiceDayCache<T> {

    /** A service day's rows are kept once it ended this long ago (late reports have arrived by then). */
    static final Duration DAY_SETTLES = Duration.ofMinutes(10);
    /** How many days back computed days are kept. */
    static final int KEEP_DAYS = 92;

    private final Clock clock;
    /** Reads the rows in {@code [from, to)}, which never spans more than one service day. */
    private final BiFunction<Instant, Instant, List<T>> query;
    private final Map<LocalDate, List<T>> days = new ConcurrentHashMap<>();

    ServiceDayCache(Clock clock, BiFunction<Instant, Instant, List<T>> query) {
        this.clock = clock;
        this.query = query;
    }

    /** The rows in {@code [from, to)}, day by day. */
    List<T> get(Instant from, Instant to) {
        Instant now = clock.instant();
        List<T> all = new ArrayList<>();
        for (LocalDate day = ServiceDay.serviceDay(from); ServiceDay.start(day).isBefore(to); day = day.plusDays(1)) {
            Instant start = ServiceDay.start(day);
            Instant end = ServiceDay.start(day.plusDays(1));
            boolean whole = !start.isBefore(from) && !end.isAfter(to) && end.plus(DAY_SETTLES).isBefore(now);
            if (whole) {
                all.addAll(days.computeIfAbsent(day, d -> query.apply(start, end)));
            } else {
                all.addAll(query.apply(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end));
            }
        }
        LocalDate oldest = ServiceDay.serviceDay(now).minusDays(KEEP_DAYS);
        days.keySet().removeIf(d -> d.isBefore(oldest));
        return all;
    }
}
