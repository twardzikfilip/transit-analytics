package com.transitanalytics.common;

import com.transitanalytics.domain.CityTime;
import com.transitanalytics.timetable.ServiceDay;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
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
    /** Nor above this table size: the database's disk may be another one, whose free space isn't known here. */
    static final long MAX_TABLE_BYTES = 1L << 30;
    /** The fingerprint of rows to be worked out again. */
    private static final String OUTDATED = "outdated";
    /** In the background, the longest pause after a day (as long as the day took, up to this). */
    private static final Duration MAX_PAUSE = Duration.ofSeconds(3);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    private final LongSupplier freeBytes;
    private final JsonMapper json = JsonMapper.builder().build();
    /** The reports whose stored rows are only marked as out of date, not deleted. */
    private final Set<String> keeping = ConcurrentHashMap.newKeySet();
    private final ReentrantLock computing = new ReentrantLock(true);
    /** By report: told of each day that is out of date, to drop what they keep of it. */
    private final Map<String, List<Consumer<LocalDate>>> listeners = new ConcurrentHashMap<>();

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
    public static DaySummaries none() {
        return new DaySummaries(null, null, null, false, () -> 0);
    }

    /** One report's stored days. */
    public final class Store<T> {
        private final String report;
        private final Supplier<String> fingerprint;
        private final JavaType type;
        /** See {@link #keeping}; null: rows can always be worked out again. */
        private final Function<T, String> key;
        /** Brings rows stored in an older form up to date, as they are read. */
        private UnaryOperator<JsonNode> upgrade = UnaryOperator.identity();

        private Store(String report, Supplier<String> fingerprint, Class<T> row, Function<T, String> key) {
            this.report = report;
            this.fingerprint = fingerprint;
            this.key = key;
            this.type = json.getTypeFactory().constructCollectionType(List.class, row);
        }

        /** Rows stored in an older form are read through {@code upgrade} (JSON rows to JSON rows). */
        public Store<T> upgrading(UnaryOperator<JsonNode> upgrade) {
            this.upgrade = upgrade;
            return this;
        }

        /** The day's rows: stored, or worked out and stored. The day must be over. */
        List<T> day(LocalDate day, Supplier<List<T>> compute) {
            return load(day).orElseGet(() -> compute(() -> load(day).orElseGet(() -> {
                long positions = enabled ? positions(day) : 0;
                List<T> rows = compute.get();
                return save(day, rows, positions);
            })));
        }

        /** The day's stored rows, when there are any and they are up to date. */
        public Optional<List<T>> load(LocalDate day) {
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
         * With a key, rows stored before stay when the new ones lack any of them: they couldn't be worked out again.
         *
         * @param positions {@link DaySummaries#positions} before the rows were worked out
         * @return the day's rows: these, or the ones kept
         */
        public List<T> save(LocalDate day, List<T> rows, long positions) {
            if (!enabled) {
                return rows;
            }
            List<T> before = key == null ? List.of() : stored(day);
            if (!before.isEmpty() && !keys(rows).containsAll(keys(before))) {
                log.info("{} of {} stays as stored: some of it can't be worked out any more", report, day);
                transaction.executeWithoutResult(status -> jdbc.update(
                        "UPDATE day_summary SET fingerprint = ?, positions = ? WHERE report = ? AND day = ?",
                        fingerprint.get(), positions, report, Date.valueOf(day)));
                return before;
            }
            if (!rows.isEmpty()) {
                write(day, rows, positions);
            }
            return rows;
        }

        private Set<String> keys(List<T> rows) {
            return rows.stream().map(key).collect(Collectors.toSet());
        }

        /** The day's rows as stored, up to date or not; none when they can't be read. */
        private List<T> stored(LocalDate day) {
            try {
                return jdbc.queryForList("SELECT content FROM day_summary WHERE report = ? AND day = ?",
                        byte[].class, report, Date.valueOf(day)).stream().findFirst().map(this::read).orElse(List.of());
            } catch (RuntimeException ex) {
                log.warn("Stored {} of {} unreadable: {}", report, day, ex.toString());
                return List.of();
            }
        }

        private void write(LocalDate day, List<T> rows, long positions) {
            long free = freeBytes.getAsLong();
            if (free < MIN_FREE_BYTES) {
                log.warn("{} of {} not stored: only {} MB of disk free", report, day, free >> 20);
                return;
            }
            Long size = jdbc.queryForObject("SELECT pg_total_relation_size('day_summary')", Long.class);
            if (size != null && size > MAX_TABLE_BYTES) {
                log.warn("{} of {} not stored: day_summary has {} MB", report, day, size >> 20);
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
                return json.convertValue(upgrade.apply(withRouteNames(json.readTree(in))), type);
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
    public <T> Store<T> store(String report, Supplier<String> fingerprint, Class<T> row) {
        return new Store<>(report, fingerprint, row, null);
    }

    /**
     * As above, for rows worked out from more than the positions: ones stored are never replaced by a day's rows that
     * lack some of them (by {@code key}), nor deleted.
     */
    public <T> Store<T> keeping(String report, Supplier<String> fingerprint, Class<T> row, Function<T, String> key) {
        keeping.add(report);
        return new Store<>(report, fingerprint, row, key);
    }

    /**
     * Rows stored before transit lines were named routes in code: their "line" keys ("line", "lines",
     * "replacementLine"…) as "route" keys, so old days read as new ones. "timeline" and "outline" are not lines.
     */
    static JsonNode withRouteNames(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode renamed = object.objectNode();
            object.properties().forEach(e -> {
                String key = e.getKey();
                boolean line = key.matches(".*(line|Line).*") && !key.matches("(?i).*(timeline|outline).*");
                renamed.set(line ? key.replace("Line", "Route").replace("line", "route") : key,
                        withRouteNames(e.getValue()));
            });
            return renamed;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode renamed = array.arrayNode();
            array.forEach(v -> renamed.add(withRouteNames(v)));
            return renamed;
        }
        return node;
    }

    /** {@code listener} is told of each day whose rows of a report are out of date. */
    public void onOutdated(String report, Consumer<LocalDate> listener) {
        listeners.computeIfAbsent(report, r -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Works something out alone: one day at a time; in the background {@link WarmUp#pauseNow} pauses. */
    public <R> R compute(Supplier<R> work) {
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
            WarmUp.pauseLater(Duration.ofNanos(Math.min(System.nanoTime() - start, MAX_PAUSE.toNanos())));
        }
        return result;
    }

    /** The positions recorded on a service day; 0 when nothing is stored. */
    public long positions(LocalDate day) {
        return !enabled ? 0 : ServiceDayCache.positionCount(jdbc)
                .apply(ServiceDay.start(day), ServiceDay.start(day.plusDays(1)));
    }

    /** Every night: drops the days with positions stored since they were worked out (a spool or archive replay). */
    @Scheduled(cron = "${app.day-summaries.check-cron:0 30 6 * * *}", zone = CityTime.ZONE_ID)
    public void check() {
        if (!enabled) {
            return;
        }
        record Stored(String report, LocalDate day, long positions) {
        }
        Map<LocalDate, Long> now = new HashMap<>();
        for (Stored stored : jdbc.query("SELECT report, day, positions FROM day_summary ORDER BY day",
                (rs, i) -> new Stored(rs.getString("report"), rs.getObject("day", LocalDate.class),
                        rs.getLong("positions")))) {
            if (stored.positions() != now.computeIfAbsent(stored.day(), this::positions)) {
                log.info("Positions of {} changed: its {} are worked out again", stored.day(), stored.report());
                delete(stored.report(), stored.day(), stored.day());
            }
        }
    }

    /**
     * Drops stored days, so they are worked out again; of reports with rows that may not be ({@link #keeping}), they
     * are marked as out of date instead.
     *
     * @param report null: all reports
     * @return how many days of reports
     */
    public int delete(String report, LocalDate from, LocalDate to) {
        if (!enabled) {
            return 0;
        }
        String[] kept = keeping.toArray(String[]::new);
        Integer dropped = transaction.execute(status -> jdbc.update("""
                        UPDATE day_summary SET fingerprint = ?
                        WHERE report = ANY (?) AND (?::text IS NULL OR report = ?) AND day BETWEEN ? AND ?""",
                OUTDATED, kept, report, report, Date.valueOf(from), Date.valueOf(to))
                + jdbc.update("""
                        DELETE FROM day_summary
                        WHERE report <> ALL (?) AND (?::text IS NULL OR report = ?) AND day BETWEEN ? AND ?""",
                kept, report, report, Date.valueOf(from), Date.valueOf(to)));
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDate outdated = day;
            listeners.forEach((of, its) -> {
                if (report == null || report.equals(of)) {
                    its.forEach(l -> l.accept(outdated));
                }
            });
        }
        return dropped == null ? 0 : dropped;
    }
}
