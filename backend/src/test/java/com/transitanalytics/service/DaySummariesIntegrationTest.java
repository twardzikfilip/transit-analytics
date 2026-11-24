package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.TripAnomalyDto.Phrase;
import com.transitanalytics.service.DelayGeneration.Run;
import com.transitanalytics.service.DelayGeneration.Stop;
import com.transitanalytics.service.StandstillHotspots.Standstill;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import com.transitanalytics.web.DaySummaryController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Summaries are written in their own transactions, so these tests remove theirs themselves. */
@DbIntegrationTest
class DaySummariesIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2001, 3, 4);
    private static final Instant AT = Instant.parse("2001-03-04T09:00:00.123456Z");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactions;

    private final AtomicInteger computed = new AtomicInteger();

    private DaySummaries summaries(long freeBytes) {
        return new DaySummaries(jdbc, transactions, Clock.systemUTC(), true, () -> freeBytes);
    }

    private List<Standstill> compute() {
        computed.incrementAndGet();
        return List.of(new Standstill(1, "103", AT, 12.5, 53.1, 23.1, null));
    }

    @AfterEach
    void removeStored() {
        jdbc.update("DELETE FROM day_summary WHERE day = ?", DAY);
    }

    @Test
    void worksADayOutOnceAndAgainWhenTheFingerprintChanges() {
        assertThat(summaries(Long.MAX_VALUE).store("test", () -> "1", Standstill.class).day(DAY, this::compute))
                .hasSize(1);
        List<Standstill> stored = summaries(Long.MAX_VALUE).store("test", () -> "1", Standstill.class)
                .day(DAY, this::compute);
        assertThat(stored).as("read back").containsExactly(new Standstill(1, "103", AT, 12.5, 53.1, 23.1, null));
        assertThat(computed).as("after a restart").hasValue(1);

        summaries(Long.MAX_VALUE).store("test", () -> "2", Standstill.class).day(DAY, this::compute);
        assertThat(computed).hasValue(2);
    }

    @Test
    void readsBackEveryReportsRows() {
        var bucket = new BusLaneReportService.Bucket(DAY, -3, 17, true, 1.5, 2.5, 3.5, -4.5, Set.of("103", "11"));
        assertThat(roundTrip(bucket, BusLaneReportService.Bucket.class)).isEqualTo(bucket);
        var segment = new FleetService.Segment(7, DAY, AT, "103", 59.5);
        assertThat(roundTrip(segment, FleetService.Segment.class)).isEqualTo(segment);
        var run = new Run(7, "230", "103", null, "Centrum", DAY, new Stop[] {new Stop("A", 53.1, 23.1)},
                new int[] {4}, new long[] {983696400L}, new int[] {-30});
        assertThat(roundTrip(run, Run.class)).usingRecursiveComparison().isEqualTo(run);
        var outcome = new Outcome("t1", "103", AT, AT.plusSeconds(60), Kind.CANCELLED, null, List.of(7L, 8L));
        assertThat(roundTrip(outcome, Outcome.class)).isEqualTo(outcome);
        // Phrases' values are only sent on as JSON, so that is what must be the same.
        JsonMapper json = JsonMapper.builder().build();
        Phrase phrase = Phrase.of("detour", "minutes", 2, "meters", 1238L, "after", "Filipowicza");
        assertThat(json.writeValueAsString(roundTrip(phrase, Phrase.class))).isEqualTo(json.writeValueAsString(phrase));
    }

    private <T> T roundTrip(T row, Class<T> type) {
        DaySummaries.Store<T> store = summaries(Long.MAX_VALUE).store("test", () -> "1", type);
        store.save(DAY, List.of(row), 0);
        return store.load(DAY).orElseThrow().get(0);
    }

    @Test
    void storesNothingOnAFullDiskNorWithoutRows() {
        summaries(DaySummaries.MIN_FREE_BYTES - 1).store("test", () -> "1", Standstill.class)
                .day(DAY, this::compute);
        summaries(Long.MAX_VALUE).store("test", () -> "1", Standstill.class).day(DAY, List::of);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM day_summary WHERE day = ?", Long.class, DAY)).isZero();
    }

    @Test
    void aStoredDayThatCantBeReadIsWorkedOutAgain() {
        DaySummaries.Store<Standstill> store = summaries(Long.MAX_VALUE).store("test", () -> "1", Standstill.class);
        store.day(DAY, this::compute);
        jdbc.update("UPDATE day_summary SET content = 'x' WHERE day = ?", DAY);

        assertThat(store.day(DAY, this::compute)).hasSize(1);
        assertThat(computed).hasValue(2);
    }

    @Test
    void dropsDaysWhosePositionsChanged() {
        DaySummaries summaries = summaries(Long.MAX_VALUE);
        List<LocalDate> outdated = new ArrayList<>();
        summaries.onOutdated(outdated::add);
        DaySummaries.Store<Standstill> store = summaries.store("test", () -> "1", Standstill.class);
        store.day(DAY, this::compute);

        summaries.check();
        assertThat(store.load(DAY)).as("no positions then, none now").isPresent();

        store.save(DAY, compute(), 5);
        summaries.check();
        assertThat(store.load(DAY)).isEmpty();
        assertThat(outdated).containsExactly(DAY);
    }

    @Test
    void dropsOneReportsDaysOnRequest() {
        DaySummaries summaries = summaries(Long.MAX_VALUE);
        DaySummaryController controller = new DaySummaryController(summaries);
        summaries.store("test", () -> "1", Standstill.class).day(DAY, this::compute);
        summaries.store("other", () -> "1", Standstill.class).day(DAY, this::compute);

        assertThat(controller.delete("test", DAY, DAY).deleted()).isEqualTo(1);
        assertThat(controller.delete(null, DAY, DAY).deleted()).isEqualTo(1);
        assertThatThrownBy(() -> controller.delete(null, DAY, DAY.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void worksOutOneDayAtATime() throws Exception {
        DaySummaries summaries = summaries(Long.MAX_VALUE);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> summaries.compute(() -> {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return running.decrementAndGet();
            }));
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(most).hasValue(1);
    }
}
