package com.transitanalytics.rail;

import com.transitanalytics.rail.JourneyPlanner.Call;
import com.transitanalytics.rail.JourneyPlanner.Change;
import com.transitanalytics.rail.JourneyPlanner.Journey;
import com.transitanalytics.rail.JourneyPlanner.Ride;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyPlannerTest {

    private static final int STOPS = 7;
    private static final IntPredicate ANY_TIME = t -> true;

    /**
     * Route 1 from 0 over 1 to 2, then route 2 on to 3 (a change at 2); route 3 from 0 to 3 directly but slowly; route 4
     * from 4 (a walk from 3) to 5; route 5 from 0 to 6, where nobody may get off; route 6 later, from 0 to 3 quickly.
     */
    private static final List<Ride> RIDES = List.of(
            ride("1", call(0, "09:00"), call(1, "09:10"), call(2, "09:20")),
            ride("2", call(2, "09:25"), call(3, "09:35")),
            ride("3", call(0, "09:00"), call(3, "10:00")),
            ride("4", call(4, "09:40"), call(5, "09:50")),
            ride("5", call(0, "09:00"), new Call(6, time("09:05"), time("09:05"), true, false)),
            ride("6", call(0, "10:00"), call(3, "10:20")));

    @Test
    void takesAChangeWhenItIsFasterThanTheDirectRide() {
        Journey journey = planner(3).fastest(Set.of(0), Map.of("3", Set.of(3)), t -> t < time("09:30")).get("3");

        assertThat(routes(journey)).containsExactly("1", "2");
        assertThat(journey.departure()).isEqualTo(time("09:00"));
        assertThat(journey.seconds()).isEqualTo(35 * 60);
    }

    @Test
    void keepsTheFastestJourneyOfAllItsDepartures() {
        Journey journey = planner(3).fastest(Set.of(0), Map.of("3", Set.of(3)), ANY_TIME).get("3");

        assertThat(routes(journey)).containsExactly("6");
        assertThat(journey.seconds()).isEqualTo(20 * 60);
    }

    @Test
    void takesNoMoreRidesThanAllowed() {
        Journey journey = planner(1).fastest(Set.of(0), Map.of("3", Set.of(3)), t -> t < time("09:30")).get("3");
        assertThat(routes(journey)).containsExactly("3");

        Map<String, Set<Integer>> five = Map.of("5", Set.of(5));
        assertThat(planner(2).fastest(Set.of(0), five, ANY_TIME)).isEmpty();
        assertThat(routes(planner(3).fastest(Set.of(0), five, ANY_TIME).get("5")))
                .as("walking from 3 to 4 to change").containsExactly("1", "2", "4");
    }

    @Test
    void leavesOutStopsOneMayNotGetOffAtAndTargetsAtTheOrigin() {
        Map<String, Journey> found = planner(3).fastest(Set.of(0, 1),
                Map.of("6", Set.of(6), "origin", Set.of(1, 2), "2", Set.of(2)), ANY_TIME);

        assertThat(found).containsOnlyKeys("2");
        assertThat(found.get("2").seconds()).as("from 1, the nearer origin").isEqualTo(10 * 60);
    }

    @Test
    void needsTimeToChange() {
        // Route 2 leaves 2 five minutes after route 1 gets there; changing takes six.
        JourneyPlanner slowChange = new JourneyPlanner(STOPS, RIDES, changes(360, 120), 3, 3600);
        Journey journey = slowChange.fastest(Set.of(0), Map.of("3", Set.of(3)), t -> t < time("09:30")).get("3");

        assertThat(routes(journey)).containsExactly("3");
    }

    private static JourneyPlanner planner(int maxRides) {
        return new JourneyPlanner(STOPS, RIDES, changes(60, 120), maxRides, 3600);
    }

    /** Changing at the same stop, or walking from 3 to 4. */
    private static List<List<Change>> changes(int sameStop, int walk) {
        List<List<Change>> changes = new ArrayList<>();
        for (int s = 0; s < STOPS; s++) {
            List<Change> from = new ArrayList<>(List.of(new Change(s, sameStop)));
            if (s == 3) {
                from.add(new Change(4, walk));
            }
            changes.add(from);
        }
        return changes;
    }

    private static List<String> routes(Journey journey) {
        return journey.legs().stream().map(l -> l.ride().route()).toList();
    }

    private static Ride ride(String route, Call... calls) {
        return new Ride(route, List.of(calls));
    }

    private static Call call(int stop, String time) {
        return new Call(stop, time(time), time(time), true, true);
    }

    private static int time(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 3600 + Integer.parseInt(hhmm.substring(3)) * 60;
    }
}
