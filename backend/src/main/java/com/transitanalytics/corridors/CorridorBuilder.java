package com.transitanalytics.corridors;

import com.transitanalytics.timetable.StopPlaces.StopRef;
import com.transitanalytics.timetable.StopPlaces;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.transitanalytics.corridors.StretchStats.*;
import static com.transitanalytics.timetable.Periods.*;
import static com.transitanalytics.timetable.StopPlaces.*;

/**
 * Builds corridors from trips: the longest stretches each set of routes shares, without shorter ones that don't carry
 * clearly more buses, joined end to end where the way on doesn't fork, and paired with their way back.
 */
final class CorridorBuilder {

    /**
     * One weekday trip: its stops in order, the departure there (seconds after midnight) and the distance along the
     * route (km, null when the feed has none).
     */
    record TripStops(long id, String route, long[] stops, int[] departures, Double[] km, String shapeId) {
        TripStops(long id, String route, long[] stops, int[] departures, Double[] km) {
            this(id, route, stops, departures, km, null);
        }
    }

    /** A corridor has at least this many stops, start and end included. */
    static final int MIN_STOPS = 4;

    /** A stretch of stops in one direction and the routes running all of it. */
    record Path(List<Long> stops, Set<String> routes) {
    }

    /** A shorter corridor inside a longer one is kept only with at least this many times its buses. */
    static final double KEEP_SUB_STRETCH_RATIO = 1.5;

    /** Weekday trips running each path's whole stop sequence. */
    static Map<Path, Integer> wholePathBuses(List<Path> paths, List<TripStops> trips) {
        Map<Long, List<TripStops>> byStop = new HashMap<>();
        for (TripStops t : trips) {
            for (long stop : new HashSet<>(java.util.Arrays.stream(t.stops()).boxed().toList())) {
                byStop.computeIfAbsent(stop, k -> new ArrayList<>()).add(t);
            }
        }
        Map<Path, Integer> buses = new HashMap<>();
        for (Path p : paths) {
            buses.put(p, (int) byStop.getOrDefault(p.stops().get(0), List.of()).stream()
                    .filter(t -> runsThrough(t.stops(), p.stops()) >= 0).count());
        }
        return buses;
    }

    /**
     * Drops a corridor that lies inside a longer one (same direction) unless it carries at least {@code ratio} times
     * the longer one's buses, e.g. where an extra route joins for a few stops: that adds a row only when it matters.
     */
    static List<Path> withoutQuietSubStretches(List<Path> paths, Map<Path, Integer> buses, double ratio) {
        Map<Long, List<Path>> byStop = new HashMap<>();
        for (Path q : paths) {
            new HashSet<>(q.stops()).forEach(stop -> byStop.computeIfAbsent(stop, k -> new ArrayList<>()).add(q));
        }
        return paths.stream().filter(p -> byStop.get(p.stops().get(0)).stream().noneMatch(q ->
                q.stops().size() > p.stops().size()
                        && buses.get(p) < ratio * buses.get(q)
                        && Collections.indexOfSubList(q.stops(), p.stops()) >= 0)).toList();
    }

    /**
     * Joins corridors end to end: where one ends on the stops another starts with (sharing one or more stops), they
     * become one, with the routes of both, as long as the way on doesn't fork there (all continuations lead to the same
     * next stop, and all ways in come from the same previous one) and no stop repeats. Repeats until nothing joins.
     */
    static List<Path> joinEndToEnd(List<Path> paths, java.util.function.Function<Long, String> name) {
        List<Path> list = new ArrayList<>(paths);
        boolean joined = true;
        while (joined) {
            joined = false;
            record Join(Path left, Path right, int overlap) {
            }
            // A corridor can only join one that starts on one of its stops.
            Map<Long, List<Path>> byFirst = new HashMap<>();
            list.forEach(b -> byFirst.computeIfAbsent(b.stops().get(0), k -> new ArrayList<>()).add(b));
            List<Join> joins = new ArrayList<>();
            for (Path a : list) {
                Set<Path> candidates = new java.util.LinkedHashSet<>();
                a.stops().forEach(stop -> candidates.addAll(byFirst.getOrDefault(stop, List.of())));
                for (Path b : candidates) {
                    int overlap = overlap(a.stops(), b.stops());
                    if (a != b && overlap > 0 && !comesBack(a.stops(), b.stops().subList(overlap, b.stops().size()),
                            name)) {
                        joins.add(new Join(a, b, overlap));
                    }
                }
            }
            // A fork is where the ways on lead to different next stops (or the ways in come from different ones).
            // Several overlapping corridors continuing along the same street are no fork: the one sharing most
            // routes (then stops) is joined.
            Map<Path, Set<Long>> nextStops = new HashMap<>();
            Map<Path, Set<Long>> previousStops = new HashMap<>();
            for (Join j : joins) {
                nextStops.computeIfAbsent(j.left(), k -> new HashSet<>()).add(j.right().stops().get(j.overlap()));
                previousStops.computeIfAbsent(j.right(), k -> new HashSet<>())
                        .add(j.left().stops().get(j.left().stops().size() - j.overlap() - 1));
            }
            Comparator<Join> best = Comparator.comparingLong((Join j) -> j.left().routes().stream()
                    .filter(j.right().routes()::contains).count()).thenComparingInt(Join::overlap);
            joins.sort(best.reversed());
            for (Join j : joins) {
                if (nextStops.get(j.left()).size() != 1 || previousStops.get(j.right()).size() != 1) {
                    continue;
                }
                List<Long> stops = new ArrayList<>(j.left().stops());
                stops.addAll(j.right().stops().subList(j.overlap(), j.right().stops().size()));
                Set<String> routes = new TreeSet<>(j.left().routes());
                routes.addAll(j.right().routes());
                list.remove(j.left());
                list.remove(j.right());
                list.add(new Path(List.copyOf(stops), routes));
                joined = true;
                break;
            }
        }
        return list;
    }

    /** Whether {@code more} stops would bring a corridor back to a stop it already passes (by name). */
    static boolean comesBack(List<Long> path, List<Long> more, java.util.function.Function<Long, String> name) {
        Set<String> names = new HashSet<>();
        path.forEach(stop -> names.add(name.apply(stop)));
        return more.stream().anyMatch(stop -> !names.add(name.apply(stop)));
    }

    /**
     * How many stops {@code a} ends with that {@code b} starts with (the longest such overlap), or 0 when they don't
     * join: no overlap, one inside the other, or joining would repeat a stop.
     */
    static int overlap(List<Long> a, List<Long> b) {
        for (int k = Math.min(a.size(), b.size()) - 1; k >= 1; k--) {
            if (a.subList(a.size() - k, a.size()).equals(b.subList(0, k))) {
                Set<Long> seen = new HashSet<>(a);
                boolean repeats = b.subList(k, b.size()).stream().anyMatch(stop -> !seen.add(stop));
                return repeats ? 0 : k;
            }
        }
        return 0;
    }

    /** The routes on each stretch between two consecutive stops (one direction): from → to → routes. */
    static Map<Long, Map<Long, Set<String>>> segments(List<TripStops> trips) {
        Map<Long, Map<Long, Set<String>>> segments = new HashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                segments.computeIfAbsent(t.stops()[i - 1], k -> new HashMap<>())
                        .computeIfAbsent(t.stops()[i], k -> new TreeSet<>()).add(t.route());
            }
        }
        return segments;
    }

    /**
     * The longest stretches shared by each set of two or more routes, with at least {@code minStops} stops. Walks
     * forward from every shared segment while at least two of its routes continue (the set narrows as routes leave),
     * then keeps only stretches that no longer stretch contains with the same or more routes.
     */
    static List<Path> paths(Map<Long, Map<Long, Set<String>>> segments, int minStops,
                            java.util.function.Function<Long, String> name) {
        Map<List<Long>, Set<String>> candidates = new LinkedHashMap<>();
        segments.forEach((from, next) -> next.forEach((to, routes) -> {
            if (routes.size() >= 2) {
                if (!name.apply(from).equals(name.apply(to))) {
                    walk(new ArrayList<>(List.of(from, to)), routes, segments, candidates, name);
                }
            }
        }));

        // Longer stretches first, indexed by stop, to find the ones containing a shorter one.
        List<Map.Entry<List<Long>, Set<String>>> all = new ArrayList<>(candidates.entrySet());
        all.sort(Comparator.comparingInt((Map.Entry<List<Long>, Set<String>> e) -> -e.getKey().size()));
        Map<Long, List<Map.Entry<List<Long>, Set<String>>>> byStop = new HashMap<>();
        for (var e : all) {
            for (Long stop : new HashSet<>(e.getKey())) {
                byStop.computeIfAbsent(stop, k -> new ArrayList<>()).add(e);
            }
        }
        List<Path> paths = new ArrayList<>();
        for (var e : all) {
            List<Long> path = e.getKey();
            boolean dominated = byStop.get(path.get(0)).stream().anyMatch(o -> o.getKey().size() > path.size()
                    && o.getValue().containsAll(e.getValue()) && Collections.indexOfSubList(o.getKey(), path) >= 0);
            if (!dominated && path.size() >= minStops) {
                paths.add(new Path(path, e.getValue()));
            }
        }
        return paths;
    }

    static void walk(List<Long> path, Set<String> routes, Map<Long, Map<Long, Set<String>>> segments,
                             Map<List<Long>, Set<String>> candidates, java.util.function.Function<Long, String> name) {
        if (path.size() >= 3) {
            candidates.put(List.copyOf(path), routes);
        }
        Long last = path.get(path.size() - 1);
        segments.getOrDefault(last, Map.of()).forEach((to, next) -> {
            // A corridor never passes the same stop twice, not even its platform on the other side of the street.
            if (path.contains(to) || path.stream().anyMatch(stop -> name.apply(stop).equals(name.apply(to)))) {
                return;
            }
            Set<String> shared = new TreeSet<>(routes);
            shared.retainAll(next);
            if (shared.size() >= 2) {
                path.add(to);
                walk(path, shared, segments, candidates, name);
                path.remove(path.size() - 1);
            }
        });
    }

    /**
     * Pairs each stretch with the one its routes run back: at least half the routes in common, and either its start where the other ends
     * and vice versa (same stop name, or less than 500 m apart), or at least half of the shorter one's stop names in
     * common (the way back can start or end a stop further on). With several, the one sharing most stop names. A
     * stretch without a way back stays alone (one-way).
     */
    static List<Path[]> pairDirections(List<Path> paths, Map<Long, StopRef> stops) {
        List<Path[]> pairs = new ArrayList<>();
        Set<Path> used = new HashSet<>();
        List<Path> ordered = new ArrayList<>(paths);
        ordered.sort(Comparator.comparingInt((Path p) -> -p.stops().size()));
        for (Path p : ordered) {
            if (used.contains(p)) {
                continue;
            }
            used.add(p);
            Set<String> names = names(p, stops);
            Path back = ordered.stream()
                    .filter(q -> !used.contains(q) && sharesRoutes(p, q) && !sameDirection(p, q, stops))
                    .filter(q -> (same(stops, first(q), last(p)) && same(stops, last(q), first(p)))
                            || 2 * common(q, names, stops) >= Math.min(names.size(), names(q, stops).size()))
                    .max(Comparator.comparingLong(q -> common(q, names, stops)))
                    .orElse(null);
            if (back != null) {
                used.add(back);
            }
            pairs.add(back == null ? new Path[]{p} : new Path[]{p, back});
        }
        return pairs;
    }

    /** Where a trip starts running the whole stretch (index of its first stop in the trip), or -1. */
    static int runsThrough(long[] tripStops, List<Long> path) {
        outer:
        for (int i = 0; i + path.size() <= tripStops.length; i++) {
            for (int j = 0; j < path.size(); j++) {
                if (tripStops[i + j] != path.get(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static Long first(Path p) {
        return p.stops().get(0);
    }

    static Long last(Path p) {
        return p.stops().get(p.stops().size() - 1);
    }

    static boolean same(Map<Long, StopRef> stops, Long a, Long b) {
        return Objects.equals(a, b) || StopPlaces.samePlace(stops.get(a), stops.get(b));
    }

    /** At least half of the smaller route set in common. */
    static boolean sharesRoutes(Path p, Path q) {
        long common = p.routes().stream().filter(q.routes()::contains).count();
        return 2 * common >= Math.min(p.routes().size(), q.routes().size());
    }

    static long common(Path q, Set<String> names, Map<Long, StopRef> stops) {
        return names(q, stops).stream().filter(names::contains).count();
    }

    /** Whether two stretches run the same way: the second's first common stop comes before its last in the first. */
    static boolean sameDirection(Path p, Path q, Map<Long, StopRef> stops) {
        List<String> a = p.stops().stream().map(s -> stops.get(s).name()).toList();
        List<String> b = q.stops().stream().map(s -> stops.get(s).name()).filter(a::contains).toList();
        return b.size() >= 2 && a.indexOf(b.get(0)) < a.indexOf(b.get(b.size() - 1));
    }

    static Set<String> names(Path p, Map<Long, StopRef> stops) {
        Set<String> names = new HashSet<>();
        p.stops().forEach(s -> names.add(stops.get(s).name()));
        return names;
    }
}
