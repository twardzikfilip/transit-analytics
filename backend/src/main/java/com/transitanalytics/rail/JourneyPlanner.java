package com.transitanalytics.rail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The fastest journeys of one day's timetable between groups of stops, with a limited number of rides (a ride is one
 * trip; every ride after the first is a change). A round-based search (as RAPTOR): round k finds the earliest
 * arrival at every stop with k rides, from the stops reached in round k - 1 plus the time to change there.
 * <p>
 * The search is run once for every time a ride leaves one of the origin stops, and the journey taking least time from
 * its first departure to its arrival is kept. Times are seconds after midnight of the service day (GTFS times, so
 * they may pass 24:00).
 */
final class JourneyPlanner {

    private static final int NEVER = Integer.MAX_VALUE;

    /** A ride's call at a stop: whether one can get on and off there. */
    record Call(int stop, int arrival, int departure, boolean boards, boolean alights) {
    }

    /** One trip of the day, named by its route (or train number). */
    record Ride(String route, List<Call> calls) {
    }

    /** Getting from one stop to another to change, or staying at the same stop, and how long that takes. */
    record Change(int to, int seconds) {
    }

    /** One ride of a journey: on at {@code calls[from]}, off at {@code calls[to]}. */
    record Leg(Ride ride, int from, int to) {

        Call on() {
            return ride.calls().get(from);
        }

        Call off() {
            return ride.calls().get(to);
        }
    }

    /** From the first departure to the last arrival. */
    record Journey(List<Leg> legs) {

        int departure() {
            return legs.get(0).on().departure();
        }

        int arrival() {
            return legs.get(legs.size() - 1).off().arrival();
        }

        int seconds() {
            return arrival() - departure();
        }
    }

    /** A departure from a stop: the ride and its call there. */
    private record Departure(int ride, int call, int time) {
    }

    private final List<Ride> rides;
    private final int[] patterns;
    private final List<List<Change>> changes;
    private final List<List<Departure>> departures;
    private final int maxRides;
    private final int horizon;
    /** Per pattern, the last search step that took a ride of it (so each pattern is taken once a stop). */
    private final int[] taken;
    private final int patternCount;
    private int stamp;

    /**
     * @param stops    how many stops there are (stop numbers are 0 to stops - 1)
     * @param rides    the day's trips
     * @param changes  per stop, where one can change to from there (including the stop itself) and how long it takes
     * @param maxRides at most this many rides a journey
     * @param horizon  rides leaving a stop later than this (in seconds) after one can board there aren't considered
     */
    JourneyPlanner(int stops, List<Ride> rides, List<List<Change>> changes, int maxRides, int horizon) {
        this.rides = List.copyOf(rides);
        this.changes = changes;
        this.maxRides = maxRides;
        this.horizon = horizon;
        this.patterns = new int[rides.size()];
        Map<List<Integer>, Integer> ids = new HashMap<>();
        this.departures = new ArrayList<>(stops);
        for (int s = 0; s < stops; s++) {
            departures.add(new ArrayList<>());
        }
        for (int r = 0; r < rides.size(); r++) {
            List<Call> calls = rides.get(r).calls();
            patterns[r] = ids.computeIfAbsent(calls.stream().map(Call::stop).toList(), k -> ids.size());
            for (int c = 0; c < calls.size() - 1; c++) {
                if (calls.get(c).boards()) {
                    departures.get(calls.get(c).stop()).add(new Departure(r, c, calls.get(c).departure()));
                }
            }
        }
        departures.forEach(list -> list.sort((a, b) -> Integer.compare(a.time(), b.time())));
        this.patternCount = ids.size();
        this.taken = new int[patternCount];
    }

    /**
     * The fastest journey from any of the origin stops to each group of target stops, leaving in the given times.
     *
     * @param origins where a journey may start
     * @param targets per name, the stops it may end at
     * @param leaves  whether a journey may leave at a time (seconds after midnight)
     * @return per target reached, the journey taking least time; the fewer rides when two take as long
     */
    Map<String, Journey> fastest(Set<Integer> origins, Map<String, Set<Integer>> targets, IntPredicate leaves) {
        Set<Integer> times = new HashSet<>();
        for (int origin : origins) {
            departures.get(origin).stream().filter(d -> leaves.test(d.time())).forEach(d -> times.add(d.time()));
        }
        List<Integer> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        Map<String, Journey> best = new HashMap<>();
        Map<String, Set<Integer>> wanted = new HashMap<>();
        targets.forEach((name, stops) -> {
            if (Collections.disjoint(stops, origins)) {
                wanted.put(name, stops);
            }
        });
        // The last ride must end at a target: only rides calling at one are worth taking then.
        boolean[] toTarget = new boolean[patternCount];
        Set<Integer> targetStops = new HashSet<>();
        wanted.values().forEach(targetStops::addAll);
        for (int r = 0; r < rides.size(); r++) {
            if (!toTarget[patterns[r]] && rides.get(r).calls().stream().anyMatch(c -> targetStops.contains(c.stop()))) {
                toTarget[patterns[r]] = true;
            }
        }
        for (int time : sorted) {
            // Once every target has a journey, arriving later than the slowest of them can't make any faster.
            int slowest = wanted.keySet().stream().allMatch(best::containsKey)
                    ? best.values().stream().mapToInt(Journey::seconds).max().orElse(0) : -1;
            Search search = new Search(origins, time, slowest < 0 ? NEVER : time + slowest, toTarget);
            wanted.forEach((name, stops) -> {
                Journey journey = search.journeyTo(stops);
                if (journey != null && leaves.test(journey.departure()) && better(journey, best.get(name))) {
                    best.put(name, journey);
                }
            });
        }
        return best;
    }

    private static boolean better(Journey journey, Journey than) {
        return than == null || journey.seconds() < than.seconds()
                || journey.seconds() == than.seconds() && journey.legs().size() < than.legs().size();
    }

    /** One search: the earliest arrivals from the origins, leaving at one time. */
    private final class Search {

        /** Per round (rides so far), the earliest arrival at each stop, and how. */
        private final int[][] arrival;
        private final int[][] rideTo;
        private final int[][] boardedAt;
        private final int[][] alightedAt;
        /** Per round, the earliest time one can board at each stop, and the stop one changed from. */
        private final int[][] ready;
        private final int[][] changedFrom;
        /** The earliest arrival at each stop in any round so far. */
        private final int[] earliest;
        /** Arrivals after this aren't wanted. */
        private final int limit;
        /** Per pattern, whether it calls at a target. */
        private final boolean[] toTarget;

        Search(Set<Integer> origins, int time, int limit, boolean[] toTarget) {
            this.limit = limit;
            this.toTarget = toTarget;
            int stops = departures.size();
            arrival = filled(maxRides + 1, stops, NEVER);
            rideTo = filled(maxRides + 1, stops, -1);
            boardedAt = filled(maxRides + 1, stops, -1);
            alightedAt = filled(maxRides + 1, stops, -1);
            ready = filled(maxRides + 1, stops, NEVER);
            changedFrom = filled(maxRides + 1, stops, -1);
            earliest = new int[stops];
            Arrays.fill(earliest, NEVER);
            List<Integer> marked = new ArrayList<>();
            for (int origin : origins) {
                ready[1][origin] = time;
                changedFrom[1][origin] = origin;
                marked.add(origin);
            }
            for (int round = 1; round <= maxRides && !marked.isEmpty(); round++) {
                List<Integer> reached = ride(round, marked);
                marked = round < maxRides ? change(round, reached) : List.of();
            }
        }

        /** Takes the first ride of each pattern from each stop one can board at, and notes where it gets to first. */
        private List<Integer> ride(int round, List<Integer> marked) {
            List<Integer> reached = new ArrayList<>();
            boolean[] isReached = new boolean[departures.size()];
            for (int stop : marked) {
                int from = ready[round][stop];
                List<Departure> list = departures.get(stop);
                stamp++;
                for (int i = firstAtOrAfter(list, from); i < list.size(); i++) {
                    Departure d = list.get(i);
                    // The first ride leaves at the search's time: a later one is another search.
                    if (d.time() > from + (round == 1 ? 0 : horizon) || d.time() >= limit) {
                        break;
                    }
                    if (taken[patterns[d.ride()]] == stamp || round == maxRides && !toTarget[patterns[d.ride()]]) {
                        continue;
                    }
                    taken[patterns[d.ride()]] = stamp;
                    List<Call> calls = rides.get(d.ride()).calls();
                    for (int c = d.call() + 1; c < calls.size(); c++) {
                        Call call = calls.get(c);
                        if (call.arrival() > limit) {
                            break;
                        }
                        if (call.alights() && call.arrival() < earliest[call.stop()]) {
                            earliest[call.stop()] = call.arrival();
                            arrival[round][call.stop()] = call.arrival();
                            rideTo[round][call.stop()] = d.ride();
                            boardedAt[round][call.stop()] = d.call();
                            alightedAt[round][call.stop()] = c;
                            if (!isReached[call.stop()]) {
                                isReached[call.stop()] = true;
                                reached.add(call.stop());
                            }
                        }
                    }
                }
            }
            return reached;
        }

        /** Where one can board for the next round, from the stops reached in this one. */
        private List<Integer> change(int round, List<Integer> reached) {
            List<Integer> marked = new ArrayList<>();
            boolean[] isMarked = new boolean[departures.size()];
            for (int stop : reached) {
                for (Change change : changes.get(stop)) {
                    int time = arrival[round][stop] + change.seconds();
                    if (time < ready[round + 1][change.to()]) {
                        ready[round + 1][change.to()] = time;
                        changedFrom[round + 1][change.to()] = stop;
                        if (!isMarked[change.to()]) {
                            isMarked[change.to()] = true;
                            marked.add(change.to());
                        }
                    }
                }
            }
            return marked;
        }

        /** The earliest arriving journey to any of the stops, the fewest rides when two arrive together. */
        Journey journeyTo(Set<Integer> stops) {
            int bestRound = -1;
            int bestStop = -1;
            for (int round = 1; round <= maxRides; round++) {
                for (int stop : stops) {
                    if (arrival[round][stop] != NEVER
                            && (bestRound < 0 || arrival[round][stop] < arrival[bestRound][bestStop])) {
                        bestRound = round;
                        bestStop = stop;
                    }
                }
            }
            if (bestRound < 0) {
                return null;
            }
            List<Leg> legs = new ArrayList<>();
            int stop = bestStop;
            for (int round = bestRound; round >= 1; round--) {
                Ride ride = rides.get(rideTo[round][stop]);
                Leg leg = new Leg(ride, boardedAt[round][stop], alightedAt[round][stop]);
                legs.add(0, leg);
                stop = changedFrom[round][leg.on().stop()];
            }
            return new Journey(legs);
        }
    }

    private static int firstAtOrAfter(List<Departure> list, int time) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).time() < time) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int[][] filled(int rows, int columns, int value) {
        int[][] table = new int[rows][columns];
        for (int[] row : table) {
            Arrays.fill(row, value);
        }
        return table;
    }
}
