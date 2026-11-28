package com.transitanalytics.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Timetables city rail lines around the trains that already run. A line runs at least once a period (two hours, an
 * hour, half an hour or 20 minutes) each way, from {@link #FIRST} to {@link #LAST}, and each of its trains keeps at
 * least {@link #HEADWAY} from every other train going the same way on the same railway, at every station of it (a
 * train may not catch up with another between two stations either). Trains going the other way aren't counted: they
 * pass each other at stations or on double track.
 * <p>
 * Each way, every minute of the period is tried as the line's pattern ("every hour at :17"): a train of the pattern
 * that would come too close to another is moved, a minute at a time, as little as it takes, keeping at most a period
 * after the previous one. The pair of patterns with the fewest trains still too close wins (where no time keeps clear
 * of all, the one coming least close is taken), then the one with the fewest trains (a moved train may add one), then
 * the one needing the fewest vehicles, each standing at least {@link #TURNAROUND} at an end, then the shortest cycle.
 * Each line's trains then count as trains already running for the lines timetabled after it. Times are seconds after
 * midnight.
 */
final class ProposalScheduler {

    static final int HEADWAY = 600;
    static final int TURNAROUND = 300;
    static final int FIRST = 5 * 3600;
    static final int LAST = 23 * 3600;
    static final int HOUR = 3600;
    private static final int MINUTE = 60;
    /** No other train on a stretch. */
    static final int NONE = Integer.MAX_VALUE;

    /** The stretch between two neighbouring stations of one railway, one way. */
    record Segment(String railway, String from, String to) {
    }

    /** A train on a stretch: when it leaves its first station and reaches its second. */
    record Pass(Segment segment, int leaves, int arrives, int train) {
    }

    /**
     * A line one way: its stations, when its trains reach and leave each (seconds after leaving the first), and the
     * stretch to the next one.
     */
    record Run(List<String> stations, int[] arrive, int[] depart, List<Segment> segments) {

        int duration() {
            return arrive[arrive.length - 1];
        }
    }

    /** One train of a line: when it leaves the first station. */
    record Trip(int train, int start) {
    }

    /** One way's trains, and how many of them come too close to another train. */
    private record Placement(List<Trip> trips, int tooClose) {
    }

    /**
     * @param minTurnaround the shortest time a vehicle stands at an end before its next trip, NONE when none does
     * @param maxTurnaround the longest, 0 when none does
     */
    record Timetable(Run out, Run back, List<Trip> outs, List<Trip> backs, int vehicles, int minTurnaround,
                     int maxTurnaround) {
    }

    private final Map<Segment, List<Pass>> occupied = new HashMap<>();
    /** At most this long between two trains of a line, each way: the pattern repeats with it. */
    private final int period;
    private int nextTrain = -1;

    /**
     * @param running the trains running already
     * @param period  seconds; a whole number of minutes
     */
    ProposalScheduler(Collection<Pass> running, int period) {
        this.period = period;
        running.forEach(p -> occupied.computeIfAbsent(p.segment(), k -> new ArrayList<>()).add(p));
    }

    /** Timetables a line both ways (back: from its last station to its first). */
    Timetable schedule(Run out, Run back) {
        Placement[] outs = new Placement[period / MINUTE];
        Placement[] backs = new Placement[period / MINUTE];
        for (int m = 0; m < outs.length; m++) {
            outs[m] = place(out, m * MINUTE, false);
            backs[m] = place(back, m * MINUTE, false);
        }
        int bestOut = 0;
        int bestBack = 0;
        long[] best = {Long.MAX_VALUE, 0, 0};
        for (int o = 0; o < outs.length; o++) {
            for (int b = 0; b < backs.length; b++) {
                // The fewest trains too close to another, then the fewest trains (a moved one may add one), then the
                // fewest vehicles running them, then the shortest cycle.
                long first = (long) (outs[o].tooClose() + backs[b].tooClose()) * 1000
                        + outs[o].trips().size() + backs[b].trips().size();
                if (first > best[0]) {
                    continue;
                }
                long[] score = {first, circulate(out, back, outs[o].trips(), backs[b].trips()).vehicles(),
                        cycle(out, back, o * MINUTE, b * MINUTE)};
                if (Arrays.compare(score, best) < 0) {
                    best = score;
                    bestOut = o;
                    bestBack = b;
                }
            }
        }
        return circulate(out, back, place(out, bestOut * MINUTE, true).trips(),
                place(back, bestBack * MINUTE, true).trips());
    }

    /** How near a train of a run leaving at a time comes to any other going the same way: NONE when there is none. */
    int closest(Run run, int start, int self) {
        int closest = NONE;
        for (int i = 0; i < run.segments().size(); i++) {
            int leaves = start + run.depart()[i];
            int arrives = start + run.arrive()[i + 1];
            for (Pass other : occupied.getOrDefault(run.segments().get(i), List.of())) {
                if (other.train() == self) {
                    continue;
                }
                int before = leaves - other.leaves();
                int after = arrives - other.arrives();
                if (Integer.signum(before) != Integer.signum(after)) {
                    return 0; // it would catch up with the other, or be caught up with
                }
                closest = Math.min(closest, Math.min(Math.abs(before), Math.abs(after)));
            }
        }
        return closest;
    }

    /** How long a vehicle takes to go out and back with the two patterns, standing long enough at each end. */
    private int cycle(Run out, Run back, int outMinute, int backMinute) {
        int far = Math.floorMod(backMinute - outMinute - out.duration(), period);
        int near = Math.floorMod(outMinute - backMinute - back.duration(), period);
        return out.duration() + back.duration() + (far < TURNAROUND ? far + period : far)
                + (near < TURNAROUND ? near + period : near);
    }

    /**
     * The trains of one way (kept, or only tried): the pattern's, each moved as little as it takes to keep clear of the
     * others, but at most a period after the previous one (the first at most half a period from its place). After a
     * train moved early, the next is the pattern's next train at least half a period later, so the line comes back to
     * its pattern.
     */
    private Placement place(Run run, int minute, boolean keep) {
        List<Trip> trips = new ArrayList<>();
        int tooClose = 0;
        Integer previous = null;
        int target = FIRST + minute;
        while (target <= LAST) {
            int earliest = previous == null ? target - period / 2 : previous + MINUTE;
            int latest = previous == null ? target + period / 2 : previous + period;
            Integer chosen = null;
            int fallback = Math.min(Math.max(target, earliest), latest);
            int fallbackClosest = -1;
            int widest = Math.max(target - earliest, latest - target);
            for (int shift = 0; shift <= widest && chosen == null; shift += MINUTE) {
                for (int start : shift == 0 ? new int[] {target} : new int[] {target - shift, target + shift}) {
                    if (start < earliest || start > latest) {
                        continue;
                    }
                    int closest = closest(run, start, 0);
                    if (closest >= HEADWAY) {
                        chosen = start;
                        break;
                    }
                    if (closest > fallbackClosest) {
                        fallback = start;
                        fallbackClosest = closest;
                    }
                }
            }
            int start = chosen != null ? chosen : fallback;
            tooClose += chosen != null ? 0 : 1;
            Trip trip = new Trip(nextTrain--, start);
            occupy(run, trip);
            trips.add(trip);
            previous = start;
            target = FIRST + minute + Math.max(0, ceilDiv(start + period / 2 - FIRST - minute, period)) * period;
            if (target > LAST && start < LAST - period) {
                target = LAST; // the pattern's next train would be too late: one more, so the line runs until LAST
            }
        }
        if (!keep) {
            for (Segment segment : run.segments()) {
                occupied.get(segment).removeIf(p -> p.train() <= trips.get(0).train()
                        && p.train() >= trips.get(trips.size() - 1).train());
            }
        }
        return new Placement(trips, tooClose);
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }

    private void occupy(Run run, Trip trip) {
        for (int i = 0; i < run.segments().size(); i++) {
            occupied.computeIfAbsent(run.segments().get(i), k -> new ArrayList<>()).add(new Pass(
                    run.segments().get(i), trip.start() + run.depart()[i], trip.start() + run.arrive()[i + 1],
                    trip.train()));
        }
    }

    /**
     * The vehicles both ways need: each trip is run by a vehicle that has stood at its first station at least the
     * turnaround time (the one that has waited longest), or by another one.
     */
    private static Timetable circulate(Run out, Run back, List<Trip> outs, List<Trip> backs) {
        record Leg(int start, int end, boolean outward) {
        }
        List<Leg> legs = new ArrayList<>();
        outs.forEach(t -> legs.add(new Leg(t.start(), t.start() + out.duration(), true)));
        backs.forEach(t -> legs.add(new Leg(t.start(), t.start() + back.duration(), false)));
        legs.sort(Comparator.comparingInt(Leg::start));
        // Per end (true: the first station), when its vehicles came in.
        Map<Boolean, PriorityQueue<Integer>> waiting = Map.of(true, new PriorityQueue<>(),
                false, new PriorityQueue<>());
        int vehicles = 0;
        int minTurnaround = NONE;
        int maxTurnaround = 0;
        for (Leg leg : legs) {
            PriorityQueue<Integer> here = waiting.get(leg.outward());
            if (!here.isEmpty() && here.peek() + TURNAROUND <= leg.start()) {
                int stood = leg.start() - here.poll();
                minTurnaround = Math.min(minTurnaround, stood);
                maxTurnaround = Math.max(maxTurnaround, stood);
            } else {
                vehicles++;
            }
            waiting.get(!leg.outward()).add(leg.end());
        }
        return new Timetable(out, back, outs, backs, vehicles, minTurnaround, maxTurnaround);
    }
}
