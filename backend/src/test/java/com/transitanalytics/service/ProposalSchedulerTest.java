package com.transitanalytics.service;

import com.transitanalytics.service.ProposalScheduler.Pass;
import com.transitanalytics.service.ProposalScheduler.Run;
import com.transitanalytics.service.ProposalScheduler.Segment;
import com.transitanalytics.service.ProposalScheduler.Timetable;
import com.transitanalytics.service.ProposalScheduler.Trip;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.transitanalytics.service.ProposalScheduler.FIRST;
import static com.transitanalytics.service.ProposalScheduler.HEADWAY;
import static com.transitanalytics.service.ProposalScheduler.LAST;
import static com.transitanalytics.service.ProposalScheduler.MAX_INTERVAL;
import static com.transitanalytics.service.ProposalScheduler.NONE;
import static com.transitanalytics.service.ProposalScheduler.TURNAROUND;
import static org.assertj.core.api.Assertions.assertThat;

class ProposalSchedulerTest {

    private static final Segment OUT = new Segment("r", "A", "B");
    private static final Segment BACK = new Segment("r", "B", "A");
    /** A to B in 10 minutes, and back. */
    private static final Run RUN_OUT = new Run(List.of("A", "B"), new int[] {0, 600}, new int[] {0, 600},
            List.of(OUT));
    private static final Run RUN_BACK = new Run(List.of("B", "A"), new int[] {0, 600}, new int[] {0, 600},
            List.of(BACK));

    @Test
    void runsAtLeastHourlyAllDayWithOneVehicleWhenNothingElseRuns() {
        Timetable t = new ProposalScheduler(List.of()).schedule(RUN_OUT, RUN_BACK);

        for (List<Trip> trips : List.of(t.outs(), t.backs())) {
            assertThat(trips.get(0).start()).isBetween(FIRST - 1800, FIRST + 3600);
            assertThat(trips.get(trips.size() - 1).start()).isBetween(LAST - 3600, LAST);
            for (int i = 1; i < trips.size(); i++) {
                assertThat(trips.get(i).start() - trips.get(i - 1).start()).isBetween(1, MAX_INTERVAL);
            }
        }
        assertThat(t.vehicles()).as("out and back within the hour").isEqualTo(1);
        assertThat(t.minTurnaround()).isGreaterThanOrEqualTo(TURNAROUND);
    }

    @Test
    void keepsTheHeadwayFromTrainsGoingTheSameWay() {
        // A train every half hour from A at :00 and :30: only :10 to :20 and :40 to :50 are clear.
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 1800) {
            running.add(new Pass(OUT, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running);
        Timetable t = scheduler.schedule(RUN_OUT, RUN_BACK);

        for (Trip trip : t.outs()) {
            assertThat(Math.floorMod(trip.start(), 1800)).isBetween(600, 1200);
            assertThat(scheduler.closest(RUN_OUT, trip.start(), trip.train())).isGreaterThanOrEqualTo(HEADWAY);
        }
    }

    @Test
    void countsTrainsGoingTheOtherWayAsNoObstacle() {
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 300) {
            running.add(new Pass(BACK, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running);

        assertThat(scheduler.closest(RUN_OUT, FIRST, 0)).isEqualTo(NONE);
    }

    @Test
    void mayNotCatchUpWithASlowerTrain() {
        // A slow train leaves A 15 minutes earlier and reaches B 5 minutes later.
        ProposalScheduler scheduler = new ProposalScheduler(List.of(new Pass(OUT, FIRST - 900, FIRST + 1200, 1)));

        assertThat(scheduler.closest(RUN_OUT, FIRST, 0)).isZero();
    }

    @Test
    void takesTheLeastCloseTimeWhenNoneKeepsTheHeadway() {
        // A train every 15 minutes: nothing keeps 10 minutes from all; 7 minutes is the most.
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 900) {
            running.add(new Pass(OUT, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running);
        Timetable t = scheduler.schedule(RUN_OUT, RUN_BACK);

        assertThat(t.outs()).isNotEmpty().allSatisfy(trip ->
                assertThat(scheduler.closest(RUN_OUT, trip.start(), trip.train())).isEqualTo(420));
    }
}
