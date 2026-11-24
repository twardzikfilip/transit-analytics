package com.transitanalytics.service;

import com.transitanalytics.service.TripAnomalies.Report;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecentReportsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final Instant START = ServiceDay.start(DAY);

    /** A clock the test moves on. */
    private static final class TestClock extends Clock {
        Instant now = START.plus(Duration.ofHours(5));

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

    /** Recorded reports, as the database holds them. */
    private static final class Recorded {
        final List<Report> reports = new ArrayList<>();
        final List<Instant> readFroms = new ArrayList<>();

        List<Report> read(Instant from, Instant to) {
            readFroms.add(from);
            return between(from, to);
        }

        List<Report> between(Instant from, Instant to) {
            return reports.stream().filter(r -> !r.time().isBefore(from) && r.time().isBefore(to))
                    .sorted(Comparator.comparingLong(Report::vehicleId).thenComparing(Report::time)).toList();
        }

        Set<Long> minutes(Instant from, Instant to) {
            Set<Long> set = new HashSet<>();
            between(from, to).forEach(r -> set.add(r.time().getEpochSecond() / 60));
            return set;
        }

        long count(Instant from, Instant to) {
            return between(from, to).size();
        }

        void add(long vehicle, Instant time, String brigade) {
            reports.add(new Report(vehicle, "B" + vehicle, time, "103", brigade, "Centrum", 30, 4, 53.1, 23.1));
        }
    }

    @Test
    void readsOnlyWhatIsNewAndGivesWhatTheDatabaseHolds() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        RecentReports recent = new RecentReports(clock, db::read, db::minutes, db::count);
        for (int i = 0; i < 60; i++) {
            db.add(1, clock.now.minusSeconds(600 - i * 10L), "103-01");
            db.add(2, clock.now.minusSeconds(600 - i * 10L), "103-02");
        }

        assertThat(recent.reports(START, clock.now)).isEqualTo(db.between(START, clock.now));
        clock.now = clock.now.plusSeconds(60);
        db.add(1, clock.now.minusSeconds(30), "103-01");
        db.add(2, clock.now.minusSeconds(125), "103-02");

        assertThat(recent.reports(START, clock.now)).as("the new ones in order, none twice")
                .isEqualTo(db.between(START, clock.now));
        assertThat(db.readFroms.get(db.readFroms.size() - 1)).as("read from shortly before the last read")
                .isEqualTo(clock.now.minusSeconds(60).minus(ServiceDayCache.REPORTS_ARRIVE));
        assertThat(recent.reports(START, clock.now, List.of("103-02"))).extracting(Report::vehicleId)
                .containsOnly(2L);
        assertThat(recent.minutes(START, clock.now)).isEqualTo(db.minutes(START, clock.now));
        assertThat(recent.lines(START, clock.now)).containsExactly("103");
        assertThat(recent.lines(START, START.plusSeconds(1))).isEmpty();
    }

    @Test
    void readsEverythingAgainWhenReportsAppearInThePartAlreadyRead() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        RecentReports recent = new RecentReports(clock, db::read, db::minutes, db::count);
        db.add(1, clock.now.minusSeconds(3600), "103-01");
        recent.reports(START, clock.now);
        long reading = recent.reading();
        clock.now = clock.now.plusSeconds(60);
        db.add(1, clock.now.minusSeconds(5), "103-01");
        assertThat(recent.reading()).as("a usual refresh").isEqualTo(reading);

        db.add(1, clock.now.minusSeconds(1800), "103-01");
        clock.now = clock.now.plusSeconds(60);

        assertThat(recent.reports(START, clock.now)).hasSize(3);
        assertThat(db.readFroms.get(db.readFroms.size() - 1)).isEqualTo(START);
        assertThat(recent.reading()).as("read again").isEqualTo(reading + 1);
        recent.forget();
        assertThat(recent.reading()).as("forgotten").isEqualTo(reading + 2);
    }

    @Test
    void keepsTheDayBeforeOnlyWhileItsNightTripsRun() {
        TestClock clock = new TestClock();
        Recorded db = new Recorded();
        RecentReports recent = new RecentReports(clock, db::read, db::minutes, db::count);
        Instant tomorrow = ServiceDay.start(DAY.plusDays(1));
        db.add(1, tomorrow.minusSeconds(60), "N1-01");
        clock.now = tomorrow.plusSeconds(600);

        assertThat(recent.covers(START, clock.now)).as("the night after the day").isTrue();
        assertThat(recent.reports(START, clock.now)).hasSize(1);

        clock.now = tomorrow.plus(RecentReports.KEEP_DAY_BEFORE).plusSeconds(60);
        db.add(2, clock.now.minusSeconds(30), "103-01");
        assertThat(recent.covers(START, clock.now)).isFalse();
        assertThat(recent.reports(tomorrow, clock.now)).extracting(Report::vehicleId).containsExactly(2L);
        assertThat(recent.size()).as("the day before dropped").isEqualTo(1);
        assertThat(recent.covers(tomorrow, clock.now.plus(Duration.ofDays(1)))).as("the future isn't kept")
                .isFalse();
    }
}
