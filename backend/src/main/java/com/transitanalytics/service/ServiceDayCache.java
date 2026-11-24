package com.transitanalytics.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Rows computed from recorded positions, read one service day (03:00–03:00) at a time.
 * <ul>
 *   <li>A day that is over doesn't change: its rows are computed once and kept. A window that cuts it takes the rows
 *   that start inside the window from the kept day (with {@link Rows}; without, the cut part is read again).</li>
 *   <li>The running day is read in pieces (with {@link Rows}): rows that are finished are kept, and each refresh reads
 *   only from the oldest row still going on. If positions are stored late in the part already kept (e.g. spooled
 *   during a database outage), the day is read again whole.</li>
 * </ul>
 * Days older than {@link #KEEP_DAYS} are dropped (an "all time" window reads them again, stored or not).
 *
 * @param <T> a row
 */
public final class ServiceDayCache<T> {

    /** A service day's rows are kept once it ended this long ago (late reports have arrived by then). */
    static final Duration DAY_SETTLES = Duration.ofMinutes(10);
    /** How many days back computed days are kept. */
    static final int KEEP_DAYS = 92;
    /** How long after it was made a report is stored, at most, while the database is up (normally seconds). */
    static final Duration REPORTS_ARRIVE = Duration.ofMinutes(2);

    /**
     * How a report's rows lie in time: what lets a cut day be taken from the whole day, and the running day be read in
     * pieces giving the same rows as reading it whole.
     *
     * @param start   when a row starts; a window takes the rows starting in it
     * @param end     when it ends (its last report, or the end of its time bucket)
     * @param join    the longest gap within a row: one that ended longer ago than this (and {@link #REPORTS_ARRIVE})
     *                can't grow any more
     * @param context how long before a row's start its query has to begin to find the row as reading the whole day
     *                does: at least {@code join} (so a row going on from before isn't taken for a new one) plus what
     *                its first element needs (e.g. the report before it)
     * @param unseen  how long a row can be going on before it shows (a run shorter than its least passages, a time
     *                bucket filling), so a refresh starts early enough to see all of it
     */
    public record Rows<T>(Function<T, Instant> start, Function<T, Instant> end, Duration join, Duration context,
                          Duration unseen) {
    }

    private final Clock clock;
    /** Reads the rows in {@code [from, to)}, which never spans more than one service day. */
    private final BiFunction<Instant, Instant, List<T>> query;
    /** Null: rows have no time, so cut days and the running day are read again for each window. */
    private final Rows<T> rows;
    /** Counts the positions recorded in {@code [from, to)}: what tells late positions in the running day. */
    private final BiFunction<Instant, Instant, Long> positions;
    /** Stored days; null: none. */
    private final DaySummaries.Store<T> store;
    private final Map<LocalDate, List<T>> days = new ConcurrentHashMap<>();
    private final Map<LocalDate, Running> running = new ConcurrentHashMap<>();

    /** Rows without a time: a window's cut days are read again. */
    public ServiceDayCache(Clock clock, BiFunction<Instant, Instant, List<T>> query) {
        this(clock, query, null, null, null);
    }

    /**
     * Rows with a time: a window's cut days come from the whole days, and the running day is read in pieces.
     *
     * @param positions counts the positions recorded in {@code [from, to)}
     */
    public ServiceDayCache(Clock clock, BiFunction<Instant, Instant, List<T>> query, Rows<T> rows,
                           BiFunction<Instant, Instant, Long> positions) {
        this(clock, query, rows, positions, null);
    }

    /** As above, with days that are over kept in {@code store} too. */
    ServiceDayCache(Clock clock, BiFunction<Instant, Instant, List<T>> query, Rows<T> rows,
                    BiFunction<Instant, Instant, Long> positions, DaySummaries.Store<T> store) {
        this.clock = clock;
        this.query = query;
        this.rows = rows;
        this.positions = positions;
        this.store = store;
    }

    /** The rows in {@code [from, to)}, day by day. */
    public List<T> get(Instant from, Instant to) {
        Instant now = clock.instant();
        List<T> all = new ArrayList<>();
        for (LocalDate day = ServiceDay.serviceDay(from); ServiceDay.start(day).isBefore(to); day = day.plusDays(1)) {
            Instant start = ServiceDay.start(day);
            Instant end = ServiceDay.start(day.plusDays(1));
            boolean whole = !start.isBefore(from) && !end.isAfter(to);
            boolean settled = end.plus(DAY_SETTLES).isBefore(now);
            if (rows == null) {
                if (whole && settled) {
                    all.addAll(days.computeIfAbsent(day, d -> query.apply(start, end)));
                } else {
                    all.addAll(query.apply(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end));
                }
                continue;
            }
            List<T> dayRows;
            if (settled) {
                running.remove(day);
                dayRows = days.computeIfAbsent(day, d -> store == null ? query.apply(start, end)
                        : store.day(d, () -> query.apply(start, end)));
            } else {
                dayRows = running.computeIfAbsent(day, Running::new).rows(now);
            }
            if (whole) {
                all.addAll(dayRows);
            } else {
                for (T row : dayRows) {
                    Instant at = rows.start().apply(row);
                    if (!at.isBefore(from) && at.isBefore(to)) {
                        all.add(row);
                    }
                }
            }
        }
        LocalDate oldest = ServiceDay.serviceDay(now).minusDays(KEEP_DAYS);
        days.keySet().removeIf(d -> d.isBefore(oldest));
        running.keySet().removeIf(d -> d.isBefore(oldest));
        return all;
    }

    /** Drops what is kept of a day: positions of it were stored late. */
    void forget(LocalDate day) {
        days.remove(day);
        running.remove(day);
    }

    /** Counts the positions recorded in {@code [from, to)} (tens of milliseconds for a day: the time index). */
    public static BiFunction<Instant, Instant, Long> positionCount(JdbcTemplate jdbc) {
        return (from, to) -> jdbc.queryForObject(
                "SELECT count(*) FROM position_sample WHERE observed_at >= ? AND observed_at < ?", Long.class,
                Timestamp.from(from), Timestamp.from(to));
    }

    /** A service day still running (or just over), read in pieces. */
    private final class Running {

        private final LocalDate day;
        private final Instant start;
        private final Instant end;
        /** The finished rows that start before {@link #cut}. */
        private final List<T> kept = new ArrayList<>();
        /** Every row starting before this is finished and kept; rows from here on are read again on a refresh. */
        private Instant cut;
        /** The positions before the next read's start, when they were last counted. */
        private long positionsBefore;

        Running(LocalDate day) {
            this.day = day;
            this.start = ServiceDay.start(day);
            this.end = ServiceDay.start(day.plusDays(1));
            this.cut = start;
        }

        /** The day's rows as of {@code now}. */
        synchronized List<T> rows(Instant now) {
            Instant counted = readFrom();
            if (cut.isAfter(start) && positions.apply(start, counted) != positionsBefore) {
                // Positions stored late in the part kept: read the day whole again.
                kept.clear();
                cut = start;
                counted = start;
                positionsBefore = 0;
            }
            List<T> fresh = new ArrayList<>();
            for (T row : query.apply(readFrom(), end)) {
                if (!rows.start().apply(row).isBefore(cut)) {
                    fresh.add(row);
                }
            }
            List<T> result = new ArrayList<>(kept);
            result.addAll(fresh);

            // Rows starting before the oldest one that can still grow (or a row not showing yet could start) are
            // finished: keep them, and read from there next time.
            Duration settles = rows.join().plus(REPORTS_ARRIVE);
            Instant next = now.minus(settles).minus(rows.unseen());
            for (T row : fresh) {
                Instant rowStart = rows.start().apply(row);
                if (!rows.end().apply(row).plus(settles).isBefore(now) && rowStart.isBefore(next)) {
                    next = rowStart;
                }
            }
            next = next.isBefore(cut) ? cut : next.isAfter(end) ? end : next;
            for (T row : fresh) {
                if (rows.start().apply(row).isBefore(next)) {
                    kept.add(row);
                }
            }
            cut = next;
            // Only what is new is counted.
            if (readFrom().isAfter(counted)) {
                positionsBefore += positions.apply(counted, readFrom());
            }
            return result;
        }

        /** Where a refresh reads from: early enough before {@link #cut} to find the rows starting there. */
        private Instant readFrom() {
            if (!cut.isAfter(start)) {
                return start;
            }
            Instant from = cut.minus(rows.context());
            return from.isBefore(start) ? start : from;
        }

        @Override
        public String toString() {
            return "running day " + day;
        }
    }
}
