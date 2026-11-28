package com.transitanalytics.corridors;

import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.timetable.StopPlaces.StopRef;
import com.transitanalytics.timetable.TimetableMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.transitanalytics.corridors.CorridorBuilder.*;
import static com.transitanalytics.timetable.Periods.*;
import static com.transitanalytics.timetable.StopPlaces.*;
import static com.transitanalytics.timetable.TimetableMath.*;

/**
 * Corridor figures measured per stretch between two stops (every bus using it) and averaged along the corridor by
 * length: buses, interval and regularity, and the routes running along it.
 */
final class StretchStats {

    /** One direction's stretch between two consecutive stops: departures from its first stop, length and routes. */
    record Stretch(List<Integer> departures, Double km, Set<String> routes) {
    }

    /** Every stretch between two consecutive stops, keyed by [from, to]. */
    static Map<List<Long>, Stretch> stretches(List<TripStops> trips) {
        Map<List<Long>, List<Integer>> departures = new HashMap<>();
        Map<List<Long>, Double> km = new HashMap<>();
        Map<List<Long>, Set<String>> routes = new HashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                List<Long> key = List.of(t.stops()[i - 1], t.stops()[i]);
                departures.computeIfAbsent(key, k -> new ArrayList<>()).add(t.departures()[i - 1]);
                routes.computeIfAbsent(key, k -> new TreeSet<>()).add(t.route());
                if (!km.containsKey(key) && t.km()[i - 1] != null && t.km()[i] != null) {
                    km.put(key, t.km()[i] - t.km()[i - 1]);
                }
            }
        }
        Map<List<Long>, Stretch> stretches = new HashMap<>();
        departures.forEach((key, times) -> stretches.put(key, new Stretch(
                times.stream().filter(x -> x >= 0).sorted().toList(), km.get(key), routes.get(key))));
        return stretches;
    }

    /**
     * One table row from one or two directions of a corridor. Buses, interval and regularity are measured on each
     * stretch between two stops (all buses using it) and averaged along the corridor, weighted by stretch length, so
     * a route joining for part of it counts for that part. Directions: buses add up, the rest is averaged.
     */
    static CorridorDto.Corridor corridor(Path[] directions, Map<List<Long>, Stretch> stretches,
                                         Map<Long, StopRef> stops) {
        double[] buses = new double[directions.length];
        List<Double> lengths = new ArrayList<>();
        List<Double> peakIntervals = new ArrayList<>(), offPeakIntervals = new ArrayList<>();
        List<Double> peakCvs = new ArrayList<>(), offPeakCvs = new ArrayList<>();
        for (int d = 0; d < directions.length; d++) {
            List<Long> path = directions[d].stops();
            List<Stretch> parts = new ArrayList<>();
            for (int i = 1; i < path.size(); i++) {
                parts.add(stretches.get(List.of(path.get(i - 1), path.get(i))));
            }
            double km = parts.stream().mapToDouble(p -> p.km() == null ? 0 : p.km()).sum();
            if (km > 0) {
                lengths.add(km);
            }
            buses[d] = along(parts, p -> (double) p.departures().size());
            addIfPresent(peakIntervals, along(parts, p -> interval(Map.of(0, p.departures()), PEAK)));
            addIfPresent(offPeakIntervals, along(parts, p -> interval(Map.of(0, p.departures()), OFF_PEAK)));
            addIfPresent(peakCvs, along(parts, p -> TimetableMath.regularity(Map.of(0, p.departures()), PEAK)));
            addIfPresent(offPeakCvs,
                    along(parts, p -> TimetableMath.regularity(Map.of(0, p.departures()), OFF_PEAK)));
        }
        // Show the busier direction; on a tie, the one whose first stop comes first alphabetically.
        int main = directions.length == 2 && (buses[1] > buses[0] || buses[1] == buses[0]
                && stops.get(first(directions[1])).name().compareTo(stops.get(first(directions[0])).name()) < 0)
                ? 1 : 0;
        Path shown = directions[main];
        Set<String> through = null;
        for (int i = 1; i < shown.stops().size(); i++) {
            Set<String> routes = stretches.get(List.of(shown.stops().get(i - 1), shown.stops().get(i))).routes();
            if (through == null) {
                through = new TreeSet<>(routes);
            } else {
                through.retainAll(routes);
            }
        }
        // Every route running along at least half of it without a break (in either direction), so a route just
        // crossing or touching it isn't listed.
        Set<String> all = new TreeSet<>();
        for (Path d : directions) {
            all.addAll(d.routes());
            all.addAll(routesAlong(d.stops(), stretches, Math.max(2, (d.stops().size()) / 2)));
        }
        List<String> names = shown.stops().stream().map(s -> stops.get(s).name()).toList();
        return new CorridorDto.Corridor(names.get(0), names.get(names.size() - 1), names,
                all.stream().sorted(TimetableMath.ROUTE_ORDER).toList(),
                through == null ? List.of() : through.stream().filter(all::contains)
                        .sorted(TimetableMath.ROUTE_ORDER).toList(),
                lengths.isEmpty() ? null : round(mean(lengths), 2),
                (int) Math.round(java.util.Arrays.stream(buses).sum()), directions.length == 1,
                peakIntervals.isEmpty() ? null : round(mean(peakIntervals), 1),
                offPeakIntervals.isEmpty() ? null : round(mean(offPeakIntervals), 1),
                peakCvs.isEmpty() ? null : round(mean(peakCvs), 2),
                offPeakCvs.isEmpty() ? null : round(mean(offPeakCvs), 2));
    }

    /** Routes running at least {@code minStretches} consecutive stretches of a path. */
    static Set<String> routesAlong(List<Long> path, Map<List<Long>, Stretch> stretches, int minStretches) {
        Map<String, Integer> run = new HashMap<>();
        Set<String> routes = new TreeSet<>();
        for (int i = 1; i < path.size(); i++) {
            Set<String> here = stretches.get(List.of(path.get(i - 1), path.get(i))).routes();
            run.keySet().retainAll(here);
            for (String route : here) {
                int length = run.merge(route, 1, Integer::sum);
                if (length >= minStretches) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /** A value averaged along stretches, weighted by their length (equally when lengths are unknown); null if none. */
    static Double along(List<Stretch> parts, java.util.function.Function<Stretch, Double> value) {
        double sum = 0;
        double weights = 0;
        boolean lengthsKnown = parts.stream().allMatch(p -> p.km() != null && p.km() > 0);
        for (Stretch p : parts) {
            Double v = value.apply(p);
            if (v != null) {
                double w = lengthsKnown ? p.km() : 1;
                sum += v * w;
                weights += w;
            }
        }
        return weights == 0 ? null : sum / weights;
    }

    static void addIfPresent(List<Double> list, Double value) {
        if (value != null) {
            list.add(value);
        }
    }

    static double mean(List<Double> values) {
        return values.stream().mapToDouble(x -> x).average().orElse(0);
    }
}
