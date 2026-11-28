package com.transitanalytics.brigades;

import com.transitanalytics.brigades.BrigadeLearning.Assignment;
import com.transitanalytics.brigades.BrigadeLearning.Learnt;
import com.transitanalytics.brigades.BrigadeLearning.LiveBrigade;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrigadeLearningTest {

    /** Monday 5 January 2099 and the days after it. */
    private static final LocalDate MONDAY = LocalDate.of(2099, 1, 5);

    @Test
    void aBrigadeIsALiveNumberOnTheRoutesOneBusRunsTogether() {
        BrigadeLearning learning = new BrigadeLearning();
        // Bus 1 runs routes 414 and 415 as "1"; bus 2 runs route 400 as "1": the same number, another bus.
        List<ScheduledTrip> trips = List.of(trip(MONDAY, "a", "414", "07:00"), trip(MONDAY, "b", "415", "08:00"),
                trip(MONDAY, "c", "400", "07:00"));
        learning.add(MONDAY, trips, reports(
                run(1, "1", trips.get(0)), run(1, "1", trips.get(1)), run(2, "1", trips.get(2))));

        Learnt learnt = learning.learnt();

        assertThat(learnt.brigades()).containsEntry(new LiveBrigade("414", "1"), "414-1")
                .containsEntry(new LiveBrigade("415", "1"), "414-1")
                .containsEntry(new LiveBrigade("400", "1"), "400-1");
        assertThat(learnt.of(MONDAY, trips.get(1))).isEqualTo(new Assignment("414-1", 1));
        assertThat(learnt.of(MONDAY, trips.get(2))).isEqualTo(new Assignment("400-1", 1));
    }

    @Test
    void aRunBelongsToTheBrigadeThatRanItOnMostDaysAlsoUnderTheIdsOfOtherDates() {
        BrigadeLearning learning = new BrigadeLearning();
        // Each date has its own trip ids, as these timetables have.
        for (int d = 0; d < 3; d++) {
            LocalDate day = MONDAY.plusDays(d);
            ScheduledTrip trip = trip(day, "t" + d, "406", "07:00");
            learning.add(day, List.of(trip), reports(run(d, d == 2 ? "2" : "1", trip)));
        }

        Learnt learnt = learning.learnt();

        assertThat(learnt.of(MONDAY.plusDays(3), trip(MONDAY.plusDays(3), "t3", "406", "07:00")))
                .as("Thursday's trip of the same run").isEqualTo(new Assignment("406-1", 2));
        assertThat(learnt.of(MONDAY.plusDays(5), trip(MONDAY.plusDays(5), "t5", "406", "07:00")))
                .as("Saturday's isn't the weekday's run").isNull();
        assertThat(learnt.of(MONDAY.plusDays(3), trip(MONDAY.plusDays(3), "t3", "406", "07:30")))
                .as("another time is another run").isNull();
    }

    @Test
    void aFewReportsOfAWrongBrigadeAreNotARun() {
        BrigadeLearning learning = new BrigadeLearning();
        ScheduledTrip a = trip(MONDAY, "a", "414", "07:00");
        ScheduledTrip b = trip(MONDAY, "b", "416", "07:00");
        List<Report> blip = run(1, "1", b).subList(0, BrigadeLearning.MIN_REPORTS - 1);
        learning.add(MONDAY, List.of(a, b), reports(run(1, "1", a), blip, run(2, "7", b)));

        Learnt learnt = learning.learnt();

        assertThat(learnt.brigades()).as("416 isn't joined to 414 by the blip")
                .containsEntry(new LiveBrigade("414", "1"), "414-1")
                .doesNotContainKey(new LiveBrigade("416", "1"));
        assertThat(learnt.of(MONDAY, b)).isEqualTo(new Assignment("416-7", 1));
    }

    @Test
    void reportsWithoutABrigadeTakeTheirBussNeighboursOrCountForNothing() {
        BrigadeLearning learning = new BrigadeLearning();
        ScheduledTrip a = trip(MONDAY, "a", "414", "07:00");
        ScheduledTrip b = trip(MONDAY, "b", "414", "08:00");
        List<Report> unnamed = run(3, "", b);
        learning.add(MONDAY, List.of(a, b), reports(run(1, "1", a), unnamed));

        assertThat(learning.learnt().runs()).hasSize(1);
        assertThat(learning.learnt().of(MONDAY, b)).isNull();
    }

    /** A trip of three stops, 10 minutes apart, leaving at {@code start} ("HH:mm") on the day. */
    private static ScheduledTrip trip(LocalDate day, String id, String route, String start) {
        Instant departure = ServiceDay.gtfsTimeZero(day).plusSeconds(LocalTime.parse(start).toSecondOfDay());
        return new ScheduledTrip(id, route, id, route + " C", departure, departure.plusSeconds(1200),
                new int[] {1, 2, 3}, new String[] {"A", "B", "C"}, new int[] {0, 600, 1200}, null);
    }

    /** A bus's reports on a trip, on time: two heading to each stop after the first. */
    private static List<Report> run(long vehicle, String brigade, ScheduledTrip trip) {
        List<Report> reports = new ArrayList<>();
        for (int seq = 2; seq <= 3; seq++) {
            for (int k = 0; k < 2; k++) {
                Instant at = trip.scheduledAt(seq).minusSeconds(60 - 20L * k);
                reports.add(new Report(vehicle, "V" + vehicle, at, trip.route(), brigade, trip.headsign(), 0, seq,
                        53.1, 23.1));
            }
        }
        return reports;
    }

    /** The reports together, by vehicle and time. */
    @SafeVarargs
    private static List<Report> reports(List<Report>... runs) {
        List<Report> all = new ArrayList<>();
        for (List<Report> run : runs) {
            all.addAll(run);
        }
        all.sort(Comparator.comparingLong(Report::vehicleId).thenComparing(Report::time));
        return all;
    }
}
