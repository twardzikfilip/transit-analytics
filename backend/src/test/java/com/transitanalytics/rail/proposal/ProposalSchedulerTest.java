package com.transitanalytics.rail.proposal;

import com.transitanalytics.rail.proposal.ProposalScheduler.Pass;
import com.transitanalytics.rail.proposal.ProposalScheduler.Run;
import com.transitanalytics.rail.proposal.ProposalScheduler.Segment;
import com.transitanalytics.rail.proposal.ProposalScheduler.Timetable;
import com.transitanalytics.rail.proposal.ProposalScheduler.Trip;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.transitanalytics.rail.proposal.ProposalScheduler.FIRST;
import static com.transitanalytics.rail.proposal.ProposalScheduler.HEADWAY;
import static com.transitanalytics.rail.proposal.ProposalScheduler.HOUR;
import static com.transitanalytics.rail.proposal.ProposalScheduler.LAST;
import static com.transitanalytics.rail.proposal.ProposalScheduler.NONE;
import static com.transitanalytics.rail.proposal.ProposalScheduler.TURNAROUND;
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
        Timetable t = new ProposalScheduler(List.of(), HOUR).schedule(RUN_OUT, RUN_BACK);

        for (List<Trip> trips : List.of(t.outs(), t.backs())) {
            assertThat(trips.get(0).start()).isBetween(FIRST - 1800, FIRST + 3600);
            assertThat(trips.get(trips.size() - 1).start()).isBetween(LAST - 3600, LAST);
            for (int i = 1; i < trips.size(); i++) {
                assertThat(trips.get(i).start() - trips.get(i - 1).start()).isBetween(1, HOUR);
            }
        }
        assertThat(t.vehicles()).as("out and back within the hour").isEqualTo(1);
        assertThat(t.minTurnaround()).isGreaterThanOrEqualTo(TURNAROUND);
        assertThat(t.maxTurnaround()).isGreaterThanOrEqualTo(t.minTurnaround()).isLessThan(2 * 3600);
    }

    @Test
    void keepsTheHeadwayFromTrainsGoingTheSameWay() {
        // A train every half hour from A at :00 and :30: only :10 to :20 and :40 to :50 are clear.
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 1800) {
            running.add(new Pass(OUT, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running, HOUR);
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
        ProposalScheduler scheduler = new ProposalScheduler(running, HOUR);

        assertThat(scheduler.closest(RUN_OUT, FIRST, 0)).isEqualTo(NONE);
    }

    @Test
    void mayNotCatchUpWithASlowerTrain() {
        // A slow train leaves A 15 minutes earlier and reaches B 5 minutes later.
        ProposalScheduler scheduler = new ProposalScheduler(List.of(new Pass(OUT, FIRST - 900, FIRST + 1200, 1)), HOUR);

        assertThat(scheduler.closest(RUN_OUT, FIRST, 0)).isZero();
    }

    @Test
    void takesTheLeastCloseTimeWhenNoneKeepsTheHeadway() {
        // A train every 15 minutes: nothing keeps 10 minutes from all; 7 minutes is the most.
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 900) {
            running.add(new Pass(OUT, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running, HOUR);
        Timetable t = scheduler.schedule(RUN_OUT, RUN_BACK);

        assertThat(t.outs()).isNotEmpty().allSatisfy(trip ->
                assertThat(scheduler.closest(RUN_OUT, trip.start(), trip.train())).isEqualTo(420));
    }

    @Test
    void runsEveryHalfHourWithOneVehicleWhenItFits() {
        Timetable t = new ProposalScheduler(List.of(), 1800).schedule(RUN_OUT, RUN_BACK);

        for (List<Trip> trips : List.of(t.outs(), t.backs())) {
            assertThat(trips).hasSizeGreaterThanOrEqualTo((LAST - FIRST) / 1800);
            for (int i = 1; i < trips.size(); i++) {
                assertThat(trips.get(i).start() - trips.get(i - 1).start()).isBetween(1, 1800);
            }
        }
        assertThat(t.vehicles()).as("10 minutes out, 10 back and 5 at each end fit in the half hour").isEqualTo(1);
    }

    @Test
    void runsEveryTwoHoursAroundOtherTrains() {
        List<Pass> running = new ArrayList<>();
        for (int start = 4 * 3600; start < 24 * 3600; start += 1800) {
            running.add(new Pass(OUT, start, start + 600, running.size() + 1));
        }
        ProposalScheduler scheduler = new ProposalScheduler(running, 7200);
        Timetable t = scheduler.schedule(RUN_OUT, RUN_BACK);

        assertThat(t.outs()).hasSizeBetween((LAST - FIRST) / 7200, (LAST - FIRST) / 7200 + 2);
        for (int i = 1; i < t.outs().size(); i++) {
            assertThat(t.outs().get(i).start() - t.outs().get(i - 1).start()).isBetween(1, 7200);
        }
        assertThat(t.outs()).allSatisfy(trip ->
                assertThat(scheduler.closest(RUN_OUT, trip.start(), trip.train())).isGreaterThanOrEqualTo(HEADWAY));
    }
}
