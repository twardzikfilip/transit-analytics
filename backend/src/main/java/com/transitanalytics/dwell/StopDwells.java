package com.transitanalytics.dwell;

import com.transitanalytics.data.Report;
import com.transitanalytics.data.ReportPair;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.hotspots.StandstillHotspots.Grid;
import com.transitanalytics.trips.Dwell;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Buses' halts at the stops along their routes, from the pairs of their reports: each visit (a bus's consecutive pairs
 * near one stop of its route) measured as a trip's stop is ({@link Dwell}), summed per route, stop and hour.
 */
final class StopDwells {

    /** Longer halts at a stop are breaks (a driver's, or waiting for the timetable), not passengers boarding. */
    static final Duration MAX_DWELL = Duration.ofMinutes(10);

    /** A route's stop: its name and place. */
    record RouteStop(String name, double lat, double lon) {
    }

    /** One hour's visits to a route's stop and their dwell, in seconds. */
    public record Hour(Instant hour, String route, String stop, int visits, long seconds) {
    }

    private final Map<String, Grid<RouteStop>> stops = new HashMap<>();
    private final Map<List<Object>, long[]> sums = new HashMap<>();
    private Visit visit;

    /** @param routeStops each route's stops along the way ({@link Timetables#stopsAlongRoutes}) */
    StopDwells(Map<String, List<Timetables.Stop>> routeStops) {
        routeStops.forEach((route, list) -> stops.put(route, new Grid<>(
                list.stream().map(s -> new RouteStop(s.name(), s.lat(), s.lon())).toList(),
                RouteStop::lat, RouteStop::lon, Dwell.AT_STOP_METRES)));
    }

    /** A bus at a stop: its first pair's start, and its steps there so far. */
    private static final class Visit {
        final long vehicle;
        final String route;
        final RouteStop stop;
        final Instant start;
        final Dwell.Visit steps = new Dwell.Visit();
        Instant last;

        Visit(long vehicle, String route, RouteStop stop, Instant start) {
            this.vehicle = vehicle;
            this.route = route;
            this.stop = stop;
            this.start = start;
        }
    }

    /** Takes the next pair (pairs come by bus and time, as {@link com.transitanalytics.data.Reports#pairs} gives them). */
    Consumer<ReportPair> taker() {
        return this::take;
    }

    void take(ReportPair pair) {
        Report a = pair.from();
        Report b = pair.to();
        double metres = Geo.meters(new double[] {a.lat(), a.lon()}, new double[] {b.lat(), b.lon()});
        double seconds = pair.seconds();
        if (!Dwell.usable(metres, seconds)) {
            close();
            return;
        }
        RouteStop stop = nearest(a.route(), a, b);
        boolean continues = visit != null && stop == visit.stop && visit.vehicle == a.vehicleId()
                && visit.route.equals(a.route()) && visit.last.equals(a.time());
        if (!continues) {
            close();
            if (stop == null) {
                return;
            }
            visit = new Visit(a.vehicleId(), a.route(), stop, a.time());
        }
        visit.steps.add(metres, seconds);
        visit.last = b.time();
    }

    /** The visits so far, summed per hour, route and stop; the last visit is closed first. */
    List<Hour> hours() {
        close();
        List<Hour> hours = new ArrayList<>();
        sums.forEach((key, s) -> hours.add(new Hour((Instant) key.get(0), (String) key.get(1), (String) key.get(2),
                (int) s[0], s[1])));
        return hours;
    }

    private void close() {
        if (visit == null) {
            return;
        }
        Integer dwell = visit.steps.seconds();
        if (dwell != null && dwell <= MAX_DWELL.toSeconds()) {
            long[] s = sums.computeIfAbsent(List.of(visit.start.truncatedTo(ChronoUnit.HOURS), visit.route,
                    visit.stop.name()), k -> new long[2]);
            s[0]++;
            s[1] += dwell;
        }
        visit = null;
    }

    /** The route's stop nearest to either report, within {@link Dwell#AT_STOP_METRES}; null when none is. */
    private RouteStop nearest(String route, Report a, Report b) {
        Grid<RouteStop> grid = stops.get(route);
        if (grid == null) {
            return null;
        }
        RouteStop best = null;
        double bestMetres = Double.MAX_VALUE;
        for (Report r : List.of(a, b)) {
            for (RouteStop s : grid.near(r.lat(), r.lon(), Dwell.AT_STOP_METRES)) {
                double m = Geo.meters(new double[] {r.lat(), r.lon()}, new double[] {s.lat(), s.lon()});
                if (m < bestMetres) {
                    best = s;
                    bestMetres = m;
                }
            }
        }
        return best;
    }
}
