package com.transitanalytics.service;

import com.transitanalytics.domain.CityTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reports' rows of service days that are over, stored in {@code day_summary}: worked out once from the recorded
 * positions and read from there after a restart. A stored day stays as it was worked out, whatever timetable is
 * imported later (imports drop past trips); it is worked out again when the report's fingerprint changes, or when
 * positions of that day turn up later ({@link #check}). Days are worked out one at a time, as each keeps a processor
 * and the database busy for seconds.
 */
@Service
public class DaySummaries {

    private static final Logger log = LoggerFactory.getLogger(DaySummaries.class);
    /** Below this much free disk space nothing is stored. */
    static final long MIN_FREE_BYTES = 5L << 30;
    /** In the background, the longest pause after a day (as long as the day took, up to this). */
    private static final Duration MAX_PAUSE = Duration.ofSeconds(3);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    private final LongSupplier freeBytes;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReentrantLock computing = new ReentrantLock(true);
    /** Told of each day that is out of date, to drop what they keep of it. */
    private final List<Consumer<LocalDate>> listeners = new CopyOnWriteArrayList<>();

    @Autowired
    public DaySummaries(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
                        @Value("${app.day-summaries.enabled:true}") boolean enabled) {
        this(jdbc, transactions, clock, enabled, () -> new File(".").getUsableSpace());
    }

    DaySummaries(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, boolean enabled,
                 LongSupplier freeBytes) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
        this.freeBytes = freeBytes;
        this.transaction = transactions == null ? null : new TransactionTemplate(transactions);
        if (transaction != null) {
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    /** Stores nothing: days are worked out by whoever asks. */
    static DaySummaries none() {
        return new DaySummaries(null, null, null, false, () -> 0);
    }

    /** One report's stored days. */
    final class Store<T> {
        private final String report;
        private final Supplier<String> fingerprint;
        private final JavaType type;

        private Store(String report, Supplier<String> fingerprint, Class<T> row) {
            this.report = report;
            this.fingerprint = fingerprint;
            this.type = json.getTypeFactory().constructCollectionType(List.class, row);
        }

        /** The day's rows: stored, or worked out and stored. The day must be over. */
        List<T> day(LocalDate day, Supplier<List<T>> compute) {
            return load(day).orElseGet(() -> compute(() -> load(day).orElseGet(() -> {
                long positions = enabled ? positions(day) : 0;
                List<T> rows = compute.get();
                save(day, rows, positions);
                return rows;
            })));
        }

        /** The day's stored rows, when there are any and they are up to date. */
        Optional<List<T>> load(LocalDate day) {
            if (!enabled) {
                return Optional.empty();
            }
            try {
                return jdbc.queryForList(
                                "SELECT content FROM day_summary WHERE report = ? AND day = ? AND fingerprint = ?",
                                byte[].class, report, Date.valueOf(day), fingerprint.get())
                        .stream().findFirst().map(this::read);
            } catch (RuntimeException ex) {
                log.warn("Stored {} of {} unreadable, worked out again: {}", report, day, ex.toString());
                return Optional.empty();
            }
        }

        /**
         * Stores a day's rows (none: nothing, they are quickly worked out again). A failure is logged only.
         *
         * @param positions {@link DaySummaries#positions} before the rows were worked out
         */
        void save(LocalDate day, List<T> rows, long positions) {
            if (!enabled || rows.isEmpty()) {
                return;
            }
            long free = freeBytes.getAsLong();
            if (free < MIN_FREE_BYTES) {
                log.warn("{} of {} not stored: only {} MB of disk free", report, day, free >> 20);
                return;
            }
            try {
                byte[] content = write(rows);
                transaction.executeWithoutResult(status -> jdbc.update("""
                        INSERT INTO day_summary (report, day, fingerprint, computed_at, positions, content)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (report, day) DO UPDATE SET fingerprint = excluded.fingerprint,
                            computed_at = excluded.computed_at, positions = excluded.positions,
                            content = excluded.content""", report, Date.valueOf(day), fingerprint.get(),
                        Timestamp.from(clock.instant()), positions, content));
            } catch (RuntimeException ex) {
                log.warn("{} of {} not stored: {}", report, day, ex.toString());
            }
        }

        private List<T> read(byte[] content) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(content))) {
                return json.readValue(in, type);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private byte[] write(List<T> rows) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
                out.write(json.writeValueAsBytes(rows));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return bytes.toByteArray();
        }
    }

    /**
     * A report's store.
     *
     * @param fingerprint of the report's logic (a version raised whenever it changes) and inputs
     */
    <T> Store<T> store(String report, Supplier<String> fingerprint, Class<T> row) {
        return new Store<>(report, fingerprint, row);
    }

    /** {@code listener} is told of each day whose rows are out of date. */
    void onOutdated(Consumer<LocalDate> listener) {
        listeners.add(listener);
    }

    /** Works something out alone: one day at a time; in the background with a pause after each. */
    <R> R compute(Supplier<R> work) {
        if (!enabled) {
            return work.get();
        }
        long start = System.nanoTime();
        R result;
        computing.lock();
        try {
            start = System.nanoTime();
            result = work.get();
        } finally {
            computing.unlock();
        }
        if (WarmUp.running()) {
            WarmUp.pause(Duration.ofNanos(Math.min(System.nanoTime() - start, MAX_PAUSE.toNanos())));
        }
        return result;
    }

    /** The positions recorded on a service day; 0 when nothing is stored. */
    long positions(LocalDate day) {
        return !enabled ? 0 : ServiceDayCache.positionCount(jdbc)
                .apply(ServiceDay.start(day), ServiceDay.start(day.plusDays(1)));
    }

    /** Every night: drops the days with positions stored since they were worked out (a spool or archive replay). */
    @Scheduled(cron = "${app.day-summaries.check-cron:0 30 6 * * *}", zone = CityTime.ZONE_ID)
    public void check() {
        if (!enabled) {
            return;
        }
        record Stored(LocalDate day, long least, long most) {
        }
        for (Stored stored : jdbc.query(
                "SELECT day, min(positions) AS least, max(positions) AS most FROM day_summary GROUP BY day",
                (rs, i) -> new Stored(rs.getObject("day", LocalDate.class), rs.getLong("least"),
                        rs.getLong("most")))) {
            long now = positions(stored.day());
            if (stored.least() != now || stored.most() != now) {
                log.info("Positions of {} changed ({} now): its reports are worked out again", stored.day(), now);
                delete(null, stored.day(), stored.day());
            }
        }
    }

    /**
     * Drops stored days, so they are worked out again.
     *
     * @param report null: all reports
     * @return how many were dropped
     */
    public int delete(String report, LocalDate from, LocalDate to) {
        if (!enabled) {
            return 0;
        }
        Integer dropped = transaction.execute(status -> jdbc.update(
                "DELETE FROM day_summary WHERE (?::text IS NULL OR report = ?) AND day BETWEEN ? AND ?",
                report, report, Date.valueOf(from), Date.valueOf(to)));
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDate outdated = day;
            listeners.forEach(l -> l.accept(outdated));
        }
        return dropped == null ? 0 : dropped;
    }
}
