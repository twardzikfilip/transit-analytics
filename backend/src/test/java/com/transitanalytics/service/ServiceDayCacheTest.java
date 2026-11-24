package com.transitanalytics.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDayCacheTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final Instant START = ServiceDay.start(DAY);
    private static final Duration JOIN = Duration.ofMinutes(15);
    private static final int MIN_REPORTS = 3;
    /** Rows as runs are: reports at most {@link #JOIN} apart form one, shown from its third report on. */
    private static final ServiceDayCache.Rows<Row> ROWS = new ServiceDayCache.Rows<>(Row::start, Row::end, JOIN,
            JOIN, JOIN.multipliedBy(MIN_REPORTS - 1));

    record Row(Instant start, Instant end, int reports) {
    }

    /** A clock the test moves on. */
    private static final class TestClock extends Clock {
        Instant now = START;

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

    /** Recorded reports (their times), as the database holds them; the query reads them as a report's SQL would. */
    private static final class Recorded {
        final List<Instant> reports = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        final List<Instant> readFroms = new ArrayList<>();

        List<Row> query(Instant from, Instant to) {
            reads.incrementAndGet();
            readFroms.add(from);
            List<Instant> in = reports.stream().filter(t -> !t.isBefore(from) && t.isBefore(to)).sorted().toList();
            List<Row> rows = new ArrayList<>();
            List<Instant> current = new ArrayList<>();
            for (Instant t : in) {
                if (!current.isEmpty() && Duration.between(current.get(current.size() - 1), t).compareTo(JOIN) > 0) {
                    add(rows, current);
                    current = new ArrayList<>();
                }
                current.add(t);
            }
            add(rows, current);
            return rows;
        }

        private static void add(List<Row> rows, List<Instant> reports) {
            if (reports.size() >= MIN_REPORTS) {
                rows.add(new Row(reports.get(0), reports.get(reports.size() - 1), reports.size()));
            }
        }

        long count(Instant from, Instant to) {
            return reports.stream().filter(t -> !t.isBefore(from) && t.isBefore(to)).count();
        }
    }

    @Test
    void readingTheRunningDayInPiecesGivesWhatReadingItWholeDoes() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        ServiceDayCache<Row> cache = new ServiceDayCache<>(clock, db::query, ROWS, db::count);
        Random random = new Random(7);

        boolean late = false;
        for (int minute = 0; minute < 20 * 60; minute++) {
            clock.now = START.plus(Duration.ofMinutes(minute));
            // Reports come every few minutes for 40 minutes, then pause 20 (as trips and layovers); some are stored
            // late, a few hours late.
            if (minute % 60 < 40 && random.nextInt(10) < 4) {
                db.reports.add(clock.now.minusSeconds(random.nextInt(60)));
            }
            if (minute > 7 * 60 && random.nextInt(500) == 0) {
                db.reports.add(clock.now.minus(Duration.ofMinutes(240 + random.nextInt(120))));
                late = true;
            }
            if (minute % 7 == 0) {
                int reads = db.reads.get();
                assertThat(cache.get(START, START.plus(Duration.ofDays(1))))
                        .as("at minute %d", minute)
                        .containsExactlyInAnyOrderElementsOf(db.query(START, START.plus(Duration.ofDays(1))));
                if (late) {
                    assertThat(db.readFroms.get(reads)).as("a report stored hours late: the day read again")
                            .isEqualTo(START);
                    late = false;
                } else if (minute > 5 * 60) {
                    Instant readFrom = db.readFroms.get(reads);
                    assertThat(readFrom).as("a refresh at minute %d reads the last hours only", minute)
                            .isAfter(clock.now.minus(Duration.ofHours(3)));
                }
            }
        }
    }

    @Test
    void takesACutDayFromTheWholeDayOnceItIsOver() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        ServiceDayCache<Row> cache = new ServiceDayCache<>(clock, db::query, ROWS, db::count);
        for (int minute = 0; minute < 120; minute += 2) {
            db.reports.add(START.plus(Duration.ofMinutes(minute + (minute >= 60 ? 30 : 0))));
        }
        clock.now = START.plus(Duration.ofDays(2));

        List<Row> morning = cache.get(START, START.plus(Duration.ofMinutes(70)));
        List<Row> later = cache.get(START.plus(Duration.ofMinutes(70)), START.plus(Duration.ofDays(1)));

        assertThat(morning).extracting(Row::start).containsExactly(START);
        assertThat(later).extracting(Row::start).containsExactly(START.plus(Duration.ofMinutes(90)));
        assertThat(db.reads).as("the day read once, whole").hasValue(1);
    }

    @Test
    void withoutRowTimesReadsACutDayAgain() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        ServiceDayCache<Row> cache = new ServiceDayCache<>(clock, db::query);
        clock.now = START.plus(Duration.ofDays(2));

        cache.get(START, START.plus(Duration.ofDays(1)));
        cache.get(START, START.plus(Duration.ofDays(1)));
        cache.get(START, START.plus(Duration.ofHours(5)));

        assertThat(db.reads).as("the whole day once, the cut part again").hasValue(2);
    }
}
