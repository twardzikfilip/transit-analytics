package com.transitanalytics.brigades;

import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.trips.TripAnomalies.Kind;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import com.transitanalytics.trips.TripMatching;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrigadeDayServiceTest {

    private static final Instant T = Instant.parse("2026-11-18T07:00:00Z");

    /** Six stops, two minutes apart: 07:00 to 07:10. */
    private static final ScheduledTrip TRIP = new ScheduledTrip("T1", "103", "103-05", "Choroszcz", T,
            T.plusSeconds(600), new int[] {1, 2, 3, 4, 5, 6}, new String[] {"A", "B", "C", "D", "E", "F"},
            new int[] {0, 120, 240, 360, 480, 600}, null);

    private static Report report(long bus, int secondsAfter, Integer delay, Integer nextSeq) {
        return new Report(bus, "B" + bus, T.plusSeconds(secondsAfter), "103", "103-05", "Choroszcz", delay, nextSeq,
                53.1, 23.1);
    }

    private static BrigadeDto.ActualRun run(String bus, int from, int to, Integer startDelay, Integer endDelay) {
        return new BrigadeDto.ActualRun(bus, from, to, "x", "y", T, T, startDelay, endDelay);
    }

    @Test
    void splitsWhatRanWhereTheBusChangesAndPlacesItByTheDelays() {
        List<BrigadeDto.ActualRun> runs = BrigadeDayService.runs(TRIP, List.of(
                // Bus 1 waiting at the first stop, then off 45 s late, to stop 3; reports without a stop are left out.
                report(1, -60, 0, 1), report(1, 50, 45, 2), report(1, 170, 50, 3), report(1, 200, null, null),
                // Bus 2 takes over heading to stop 5 (90 s late), leaves it 30 s late and arrives 30 s late.
                report(2, 560, 90, 5), report(2, 630, 30, 6)));

        assertThat(runs).hasSize(2);
        BrigadeDto.ActualRun first = runs.get(0);
        assertThat(first.bus()).isEqualTo("B1");
        assertThat(first.fromStop()).isEqualTo(1);
        assertThat(first.toStop()).isEqualTo(3);
        assertThat(first.fromName()).isEqualTo("A");
        assertThat(first.toName()).isEqualTo("C");
        assertThat(first.startDelay()).as("once it left the first stop").isEqualTo(45);
        assertThat(first.endDelay()).isEqualTo(50);
        assertThat(first.departure()).isEqualTo(T.plusSeconds(45));
        assertThat(first.arrival()).isEqualTo(T.plusSeconds(240 + 50));
        BrigadeDto.ActualRun second = runs.get(1);
        assertThat(second.bus()).isEqualTo("B2");
        assertThat(second.fromStop()).isEqualTo(5);
        assertThat(second.startDelay()).as("once it left its first stop").isEqualTo(30);
        assertThat(second.departure()).isEqualTo(T.plusSeconds(480 + 30));
        assertThat(second.arrival()).isEqualTo(T.plusSeconds(600 + 30));

        // Without delays: the reports' own times; an arrival never before the departure.
        BrigadeDto.ActualRun bare = BrigadeDayService.runs(TRIP, List.of(report(3, 100, null, 2),
                report(3, 90, null, 2))).get(0);
        assertThat(bare.departure()).isEqualTo(T.plusSeconds(90));
        assertThat(bare.arrival()).isEqualTo(T.plusSeconds(100));
        BrigadeDto.ActualRun backwards = BrigadeDayService.runs(TRIP, List.of(report(4, 0, 300, 1),
                report(4, 10, -200, 1))).get(0);
        assertThat(backwards.arrival()).isEqualTo(backwards.departure());
        assertThat(BrigadeDayService.runs(TRIP, List.of())).isEmpty();
    }

    @Test
    void endsTheTripWhenTheBusReachesItsLastStopNotWhenItLeavesForTheNextOne() {
        // The last stop at 53.1, 23.1; the feed keeps the bus waiting there on the trip, its delay growing.
        ScheduledTrip trip = new ScheduledTrip("T1", "22", "022-01", "Centrum", T, T.plusSeconds(600),
                new int[] {1, 2, 3, 4, 5, 6}, new String[] {"A", "B", "C", "D", "E", "F"},
                new int[] {0, 120, 240, 360, 480, 600}, null, new double[] {53.1, 23.1});
        List<Report> reports = List.of(
                at(1, 300, 10, 5, 53.11),
                // Heading to the last stop, 400 m out: still running.
                at(1, 540, -30, 6, 53.1036),
                // 56 m from it: arrived, a minute early.
                at(1, 570, -60, 6, 53.1005),
                at(1, 900, 300, 6, 53.1004),
                at(1, 1800, 1200, 6, 53.1004),
                // Another bus on the same trip keeps its own reports.
                at(2, 100, 0, 2, 53.15));

        List<Report> kept = TripMatching.withoutLayover(trip, reports);
        assertThat(kept).extracting(Report::time).containsExactly(T.plusSeconds(300), T.plusSeconds(540),
                T.plusSeconds(570), T.plusSeconds(100));
        BrigadeDto.ActualRun run = BrigadeDayService.runs(trip, kept).stream()
                .filter(r -> r.bus().equals("B1")).findFirst().orElseThrow();
        assertThat(run.endDelay()).isEqualTo(-60);
        assertThat(run.arrival()).isEqualTo(T.plusSeconds(540));

        // Without the last stop's position, nothing is left out.
        assertThat(TripMatching.withoutLayover(TRIP, reports)).isSameAs(reports);
    }

    private static Report at(long bus, int secondsAfter, int delay, int nextSeq, double lat) {
        return new Report(bus, "B" + bus, T.plusSeconds(secondsAfter), "22", "022-01", "Centrum", delay, nextSeq,
                lat, 23.1);
    }

    @Test
    void marksThePartsNobodyRanOrNoBusWasSeenOn() {
        // Missed or cancelled: the whole trip.
        assertThat(BrigadeDayService.missing(TRIP, Kind.MISSED, List.of())).singleElement()
                .isEqualTo(new BrigadeDto.Missing(1, 6, T, T.plusSeconds(600), "not-run"));
        assertThat(BrigadeDayService.missing(TRIP, Kind.CANCELLED, List.of())).hasSize(1);
        // Lost at stop 3: stops 3 to 6.
        assertThat(BrigadeDayService.missing(TRIP, Kind.LOST, List.of(run("B1", 1, 3, 0, 0)))).singleElement()
                .isEqualTo(new BrigadeDto.Missing(3, 6, T.plusSeconds(240), T.plusSeconds(600), "not-run"));
        // Turned short: joined at stop 4.
        assertThat(BrigadeDayService.missing(TRIP, Kind.SHORT_TURNED, List.of(run("B1", 4, 6, 0, 0))))
                .singleElement().extracting(BrigadeDto.Missing::fromStop, BrigadeDto.Missing::toStop)
                .containsExactly(1, 4);
        // Bus changed with stops skipped between: only the gap (the replacement finished the trip).
        assertThat(BrigadeDayService.missing(TRIP, Kind.VEHICLE_CHANGED,
                List.of(run("B1", 1, 2, 0, 0), run("B2", 5, 6, 0, 0)))).singleElement()
                .isEqualTo(new BrigadeDto.Missing(2, 5, T.plusSeconds(120), T.plusSeconds(480), "not-run"));
        // A bus not seen for part: not seen, not "not run".
        assertThat(BrigadeDayService.missing(TRIP, Kind.NO_SIGNAL, List.of(run("B1", 1, 4, 0, 0))))
                .singleElement().extracting(BrigadeDto.Missing::why).isEqualTo("not-seen");
        // Run in full (it may end a stop short of its last report), upcoming or not recorded: nothing missing.
        assertThat(BrigadeDayService.missing(TRIP, Kind.COMPLETED, List.of(run("B1", 1, 5, 0, 0)))).isEmpty();
        assertThat(BrigadeDayService.missing(TRIP, Kind.PENDING, List.of())).isEmpty();
        assertThat(BrigadeDayService.missing(TRIP, Kind.LOST, List.of())).isEmpty();
    }

    private static BrigadeDto.Trip trip(String kind, BrigadeDto.ActualRun... runs) {
        return new BrigadeDto.Trip("T", "103", "Choroszcz", "A", "F", 0, T, T.plusSeconds(600), 6, kind, null,
                List.of(runs), List.of());
    }

    @Test
    void summarisesTheDay() {
        BrigadeDto.Summary s = BrigadeDayService.summary(List.of(
                trip("completed", run("209", 1, 6, 45, 120)),
                trip("completed", run("209", 1, 6, -90, 30)),
                trip("vehicle-changed", run("209", 1, 3, 300, 400), run("486", 4, 6, 600, 650)),
                trip("missed"),
                trip("not-recorded", run("486", 1, 6, 10, 10)),
                trip("pending")));

        assertThat(s.trips()).isEqualTo(6);
        assertThat(s.judged()).isEqualTo(4);
        assertThat(s.completed()).isEqualTo(2);
        assertThat(s.abnormal()).isEqualTo(2);
        assertThat(s.withStartDelay()).as("judged trips that ran").isEqualTo(3);
        assertThat(s.onTime()).as("45 s late; 90 s early and 5 min late aren't").isEqualTo(1);
        assertThat(s.medianStartDelay()).isEqualTo(45);
        assertThat(s.maxDelay()).isEqualTo(650);
        assertThat(s.buses()).containsExactly("209", "486");
        assertThat(BrigadeDayService.summary(List.of()).medianStartDelay()).isNull();
    }
}
