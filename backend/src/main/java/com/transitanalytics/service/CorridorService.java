package com.transitanalytics.service;

import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.service.LineDetailService.StopRef;
import com.transitanalytics.service.Periods.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Important corridors: stretches of consecutive stops that two or more lines share, from the regular weekday
 * timetable. Every set of lines gets the longest stretches it shares, so corridors can overlap (lines A and B along a
 * street, and A, B and C on part of it). Each corridor has its length, buses a day, and the interval and regularity of
 * those buses in peak and off-peak hours, with both directions in one row.
 */
@Service
public class CorridorService {

    /** A corridor has at least this many stops, start and end included. */
    static final int MIN_STOPS = 4;
    static final List<Window> PEAK = Periods.PEAK;
    static final List<Window> OFF_PEAK = Periods.OFF_PEAK;

    /**
     * One weekday trip: its stops in order, the departure there (seconds after midnight) and the distance along the
     * route (km, null when the feed has none).
     */
    record TripStops(long id, String line, long[] stops, int[] departures, Double[] km, String shapeId) {
        TripStops(long id, String line, long[] stops, int[] departures, Double[] km) {
            this(id, line, stops, departures, km, null);
        }
    }

    /** A point of a route shape: position and distance along the shape (km). */
    record ShapePoint(double lat, double lon, Double km) {
    }

    /** A stretch of stops in one direction and the lines running all of it. */
    record Path(List<Long> stops, Set<String> lines) {
    }

    private final JdbcTemplate jdbc;
    private final WeekdayTimetable weekdayTimetable;
    private final Map<String, CorridorDto.Report> cache = new ConcurrentHashMap<>();

    public CorridorService(JdbcTemplate jdbc, WeekdayTimetable weekdayTimetable) {
        this.jdbc = jdbc;
        this.weekdayTimetable = weekdayTimetable;
    }

    /** Computes the corridors once at startup, in the background, so the first visit doesn't wait for it. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUp() {
        Thread thread = new Thread(() -> {
            try {
                corridors();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(CorridorService.class).warn("Corridors not precomputed", e);
            }
        }, "corridors-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    @Transactional(readOnly = true)
    public CorridorDto.Report corridors() {
        String service = weekdayTimetable.serviceId();
        if (service == null) {
            return new CorridorDto.Report(null, periods(PEAK), periods(OFF_PEAK), List.of(), List.of());
        }
        // The result only changes with the timetable: keyed by the service and its trips, so an import refreshes it.
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT count(*) AS trips, coalesce(max(id), 0) AS last FROM trip WHERE service_id = ?", service);
        String key = service + ":" + version.get("trips") + ":" + version.get("last");
        CorridorDto.Report report = cache.get(key);
        if (report == null) {
            // One computation at a time (the startup warm-up and a first visit may overlap); only the newest is kept.
            synchronized (cache) {
                report = cache.get(key);
                if (report == null) {
                    report = compute(service);
                    cache.clear();
                    cache.put(key, report);
                }
            }
        }
        return report;
    }

    private CorridorDto.Report compute(String service) {
        Map<Long, StopRef> stops = new HashMap<>();
        Map<Long, List<Object[]>> rows = new LinkedHashMap<>();
        Map<Long, String> lineOf = new HashMap<>();
        Map<Long, String> shapeOf = new HashMap<>();
        jdbc.query("""
                SELECT t.id AS trip, t.shape_id, coalesce(nullif(r.short_name, ''), r.long_name) AS line, st.stop_id,
                       s.name,
                       s.latitude, s.longitude, extract(epoch FROM st.departure_time::interval)::int AS departure,
                       st.shape_dist_traveled AS km
                FROM trip t
                JOIN route r ON r.id = t.route_id
                JOIN stop_time st ON st.trip_id = t.id
                JOIN stop s ON s.id = st.stop_id
                WHERE t.service_id = ?
                ORDER BY t.id, st.stop_sequence""",
                rs -> {
                    long stop = rs.getLong("stop_id");
                    if (!stops.containsKey(stop)) {
                        stops.put(stop, new StopRef(LineDetailService.stopName(rs.getString("name")),
                                (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")));
                    }
                    long trip = rs.getLong("trip");
                    lineOf.put(trip, rs.getString("line"));
                    shapeOf.put(trip, rs.getString("shape_id"));
                    rows.computeIfAbsent(trip, k -> new ArrayList<>()).add(new Object[]{stop,
                            rs.getObject("departure") == null ? -1 : rs.getInt("departure"),
                            rs.getObject("km") == null ? null : rs.getDouble("km")});
                },
                service);
        List<TripStops> trips = new ArrayList<>();
        rows.forEach((id, list) -> {
            long[] s = new long[list.size()];
            int[] d = new int[list.size()];
            Double[] km = new Double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                s[i] = (Long) list.get(i)[0];
                d[i] = (Integer) list.get(i)[1];
                km[i] = (Double) list.get(i)[2];
            }
            trips.add(new TripStops(id, lineOf.get(id), s, d, km, shapeOf.get(id)));
        });

        Map<String, List<ShapePoint>> shapes = new HashMap<>();
        jdbc.query("""
                SELECT sp.shape_id, sp.latitude, sp.longitude, sp.shape_dist_traveled
                FROM shape_point sp
                WHERE sp.shape_id IN (SELECT DISTINCT shape_id FROM trip WHERE service_id = ?)
                ORDER BY sp.shape_id, sp.sequence""",
                rs -> {
                    shapes.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>()).add(new ShapePoint(
                            rs.getDouble("latitude"), rs.getDouble("longitude"),
                            (Double) rs.getObject("shape_dist_traveled")));
                },
                service);

        return new CorridorDto.Report(service, periods(PEAK), periods(OFF_PEAK), corridors(trips, stops),
                segmentLoads(trips, stops, shapes));
    }

    private static List<com.transitanalytics.dto.LineStatsDto.Period> periods(List<Window> windows) {
        return Periods.toDto(windows);
    }

    // --- pure helpers (unit tested) ------------------------------------------------------------------------------

    /**
     * The corridors of these trips, busiest first: the longest stretches shared by each set of lines, without shorter
     * ones that don't carry clearly more buses than a longer one around them, then joined end to end where the way on
     * doesn't fork.
     */
    static List<CorridorDto.Corridor> corridors(List<TripStops> trips, Map<Long, StopRef> stops) {
        java.util.function.Function<Long, String> name = id -> stops.get(id).name();
        List<Path> paths = paths(segments(trips), MIN_STOPS, name);
        List<Path> kept = withoutQuietSubStretches(paths, wholePathBuses(paths, trips), KEEP_SUB_STRETCH_RATIO);
        List<Path> merged = joinEndToEnd(kept, name);
        Map<List<Long>, Stretch> stretches = stretches(trips);
        List<CorridorDto.Corridor> result = new ArrayList<>();
        for (Path[] pair : pairDirections(merged, stops)) {
            result.add(corridor(pair, stretches, stops));
        }
        result.sort(Comparator.comparingInt(CorridorDto.Corridor::buses).reversed()
                .thenComparing(c -> -c.stops().size())
                .thenComparing(CorridorDto.Corridor::from));
        return result;
    }

    /** A shorter corridor inside a longer one is kept only with at least this many times its buses. */
    static final double KEEP_SUB_STRETCH_RATIO = 1.5;

    /** One direction's stretch between two consecutive stops: departures from its first stop, length and lines. */
    record Stretch(List<Integer> departures, Double km, Set<String> lines) {
    }

    /** Every stretch between two consecutive stops, keyed by [from, to]. */
    static Map<List<Long>, Stretch> stretches(List<TripStops> trips) {
        Map<List<Long>, List<Integer>> departures = new HashMap<>();
        Map<List<Long>, Double> km = new HashMap<>();
        Map<List<Long>, Set<String>> lines = new HashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                List<Long> key = List.of(t.stops()[i - 1], t.stops()[i]);
                departures.computeIfAbsent(key, k -> new ArrayList<>()).add(t.departures()[i - 1]);
                lines.computeIfAbsent(key, k -> new TreeSet<>()).add(t.line());
                if (!km.containsKey(key) && t.km()[i - 1] != null && t.km()[i] != null) {
                    km.put(key, t.km()[i] - t.km()[i - 1]);
                }
            }
        }
        Map<List<Long>, Stretch> stretches = new HashMap<>();
        departures.forEach((key, times) -> stretches.put(key, new Stretch(
                times.stream().filter(x -> x >= 0).sorted().toList(), km.get(key), lines.get(key))));
        return stretches;
    }

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
     * the longer one's buses, e.g. where an extra line joins for a few stops: that adds a row only when it matters.
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
     * become one, with the lines of both, as long as the way on doesn't fork there (all continuations lead to the same
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
            // lines (then stops) is joined.
            Map<Path, Set<Long>> nextStops = new HashMap<>();
            Map<Path, Set<Long>> previousStops = new HashMap<>();
            for (Join j : joins) {
                nextStops.computeIfAbsent(j.left(), k -> new HashSet<>()).add(j.right().stops().get(j.overlap()));
                previousStops.computeIfAbsent(j.right(), k -> new HashSet<>())
                        .add(j.left().stops().get(j.left().stops().size() - j.overlap() - 1));
            }
            Comparator<Join> best = Comparator.comparingLong((Join j) -> j.left().lines().stream()
                    .filter(j.right().lines()::contains).count()).thenComparingInt(Join::overlap);
            joins.sort(best.reversed());
            for (Join j : joins) {
                if (nextStops.get(j.left()).size() != 1 || previousStops.get(j.right()).size() != 1) {
                    continue;
                }
                List<Long> stops = new ArrayList<>(j.left().stops());
                stops.addAll(j.right().stops().subList(j.overlap(), j.right().stops().size()));
                Set<String> lines = new TreeSet<>(j.left().lines());
                lines.addAll(j.right().lines());
                list.remove(j.left());
                list.remove(j.right());
                list.add(new Path(List.copyOf(stops), lines));
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

    /** The lines on each stretch between two consecutive stops (one direction): from → to → lines. */
    static Map<Long, Map<Long, Set<String>>> segments(List<TripStops> trips) {
        Map<Long, Map<Long, Set<String>>> segments = new HashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                segments.computeIfAbsent(t.stops()[i - 1], k -> new HashMap<>())
                        .computeIfAbsent(t.stops()[i], k -> new TreeSet<>()).add(t.line());
            }
        }
        return segments;
    }

    /**
     * The longest stretches shared by each set of two or more lines, with at least {@code minStops} stops. Walks
     * forward from every shared segment while at least two of its lines continue (the set narrows as lines leave),
     * then keeps only stretches that no longer stretch contains with the same or more lines.
     */
    static List<Path> paths(Map<Long, Map<Long, Set<String>>> segments, int minStops,
                            java.util.function.Function<Long, String> name) {
        Map<List<Long>, Set<String>> candidates = new LinkedHashMap<>();
        segments.forEach((from, next) -> next.forEach((to, lines) -> {
            if (lines.size() >= 2) {
                if (!name.apply(from).equals(name.apply(to))) {
                    walk(new ArrayList<>(List.of(from, to)), lines, segments, candidates, name);
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

    private static void walk(List<Long> path, Set<String> lines, Map<Long, Map<Long, Set<String>>> segments,
                             Map<List<Long>, Set<String>> candidates, java.util.function.Function<Long, String> name) {
        if (path.size() >= 3) {
            candidates.put(List.copyOf(path), lines);
        }
        Long last = path.get(path.size() - 1);
        segments.getOrDefault(last, Map.of()).forEach((to, next) -> {
            // A corridor never passes the same stop twice, not even its platform on the other side of the street.
            if (path.contains(to) || path.stream().anyMatch(stop -> name.apply(stop).equals(name.apply(to)))) {
                return;
            }
            Set<String> shared = new TreeSet<>(lines);
            shared.retainAll(next);
            if (shared.size() >= 2) {
                path.add(to);
                walk(path, shared, segments, candidates, name);
                path.remove(path.size() - 1);
            }
        });
    }

    /**
     * Pairs each stretch with the one its lines run back: at least half the lines in common, and either its start where the other ends
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
                    .filter(q -> !used.contains(q) && sharesLines(p, q) && !sameDirection(p, q, stops))
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

    /**
     * One table row from one or two directions of a corridor. Buses, interval and regularity are measured on each
     * stretch between two stops (all buses using it) and averaged along the corridor, weighted by stretch length, so
     * a line joining for part of it counts for that part. Directions: buses add up, the rest is averaged.
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
            Set<String> lines = stretches.get(List.of(shown.stops().get(i - 1), shown.stops().get(i))).lines();
            if (through == null) {
                through = new TreeSet<>(lines);
            } else {
                through.retainAll(lines);
            }
        }
        // Every line running along at least half of it without a break (in either direction), so a line just
        // crossing or touching it isn't listed.
        Set<String> all = new TreeSet<>();
        for (Path d : directions) {
            all.addAll(d.lines());
            all.addAll(linesAlong(d.stops(), stretches, Math.max(2, (d.stops().size()) / 2)));
        }
        List<String> names = shown.stops().stream().map(s -> stops.get(s).name()).toList();
        return new CorridorDto.Corridor(names.get(0), names.get(names.size() - 1), names,
                all.stream().sorted(TimetableMath.LINE_ORDER).toList(),
                through == null ? List.of() : through.stream().filter(all::contains)
                        .sorted(TimetableMath.LINE_ORDER).toList(),
                lengths.isEmpty() ? null : round(mean(lengths), 2),
                (int) Math.round(java.util.Arrays.stream(buses).sum()), directions.length == 1,
                peakIntervals.isEmpty() ? null : round(mean(peakIntervals), 1),
                offPeakIntervals.isEmpty() ? null : round(mean(offPeakIntervals), 1),
                peakCvs.isEmpty() ? null : round(mean(peakCvs), 2),
                offPeakCvs.isEmpty() ? null : round(mean(offPeakCvs), 2));
    }

    /** Lines running at least {@code minStretches} consecutive stretches of a path. */
    static Set<String> linesAlong(List<Long> path, Map<List<Long>, Stretch> stretches, int minStretches) {
        Map<String, Integer> run = new HashMap<>();
        Set<String> lines = new TreeSet<>();
        for (int i = 1; i < path.size(); i++) {
            Set<String> here = stretches.get(List.of(path.get(i - 1), path.get(i))).lines();
            run.keySet().retainAll(here);
            for (String line : here) {
                int length = run.merge(line, 1, Integer::sum);
                if (length >= minStretches) {
                    lines.add(line);
                }
            }
        }
        return lines;
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

    private static void addIfPresent(List<Double> list, Double value) {
        if (value != null) {
            list.add(value);
        }
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(x -> x).average().orElse(0);
    }

    private static double round(double value, int decimals) {
        return TimetableMath.round(value, decimals);
    }

    /**
     * Every stretch between two consecutive stops with its weekday buses, in peak hours and all day, for the maps.
     * Both directions are one stretch when their stops have the same names; the line follows the busier direction's
     * route shape (a straight line when the feed has no shape).
     */
    static List<CorridorDto.SegmentLoad> segmentLoads(List<TripStops> trips, Map<Long, StopRef> stops,
                                                       Map<String, List<ShapePoint>> shapes) {
        // Directed stretches: buses in peak, all day, and one trip (with its stop index) to take the shape from.
        record Directed(long from, long to, int[] counts, TripStops trip, int at) {
        }
        Map<List<Long>, Directed> directed = new LinkedHashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                int at = i - 1;
                Directed d = directed.computeIfAbsent(List.of(t.stops()[at], t.stops()[i]),
                        k -> new Directed(k.get(0), k.get(1), new int[2], t, at));
                int departure = t.departures()[at];
                if (Periods.in(PEAK, departure)) {
                    d.counts()[0]++;
                }
                d.counts()[1]++;
            }
        }
        // Both directions of a stretch together, keyed by its two stop names in either order.
        Map<String, List<Directed>> byNames = new LinkedHashMap<>();
        for (Directed d : directed.values()) {
            String a = stops.get(d.from()).name();
            String b = stops.get(d.to()).name();
            if (a.equals(b)) {
                continue;
            }
            byNames.computeIfAbsent(a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a,
                    k -> new ArrayList<>()).add(d);
        }
        List<CorridorDto.SegmentLoad> loads = new ArrayList<>();
        for (List<Directed> group : byNames.values()) {
            Directed busiest = group.stream().max(Comparator.comparingInt(d -> d.counts()[1])).orElseThrow();
            loads.add(new CorridorDto.SegmentLoad(stops.get(busiest.from()).name(), stops.get(busiest.to()).name(),
                    group.stream().mapToInt(d -> d.counts()[0]).sum(), group.stream().mapToInt(d -> d.counts()[1]).sum(),
                    shapeBetween(busiest.trip(), busiest.at(), stops, shapes)));
        }
        loads.sort(Comparator.comparingInt(CorridorDto.SegmentLoad::peakBuses));
        return loads;
    }

    /** The route between a trip's stop {@code at} and the next one: its shape cut at the two stops' distances. */
    static List<double[]> shapeBetween(TripStops trip, int at, Map<Long, StopRef> stops,
                                       Map<String, List<ShapePoint>> shapes) {
        StopRef a = stops.get(trip.stops()[at]);
        StopRef b = stops.get(trip.stops()[at + 1]);
        List<double[]> points = new ArrayList<>();
        if (a.located()) {
            points.add(new double[]{TimetableMath.round(a.lat(), 5), TimetableMath.round(a.lon(), 5)});
        }
        Double from = trip.km()[at];
        Double to = trip.km()[at + 1];
        List<ShapePoint> shape = trip.shapeId() == null ? null : shapes.get(trip.shapeId());
        if (shape != null && from != null && to != null) {
            for (ShapePoint p : shape) {
                if (p.km() != null && p.km() > from && p.km() < to) {
                    points.add(new double[]{TimetableMath.round(p.lat(), 5), TimetableMath.round(p.lon(), 5)});
                }
            }
        }
        if (b.located()) {
            points.add(new double[]{TimetableMath.round(b.lat(), 5), TimetableMath.round(b.lon(), 5)});
        }
        return points;
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

    static Double interval(Map<Integer, List<Integer>> departures, List<Window> windows) {
        return TimetableMath.interval(departures, windows);
    }

    private static Long first(Path p) {
        return p.stops().get(0);
    }

    private static Long last(Path p) {
        return p.stops().get(p.stops().size() - 1);
    }

    private static boolean same(Map<Long, StopRef> stops, Long a, Long b) {
        return Objects.equals(a, b) || LineDetailService.samePlace(stops.get(a), stops.get(b));
    }

    /** At least half of the smaller line set in common. */
    private static boolean sharesLines(Path p, Path q) {
        long common = p.lines().stream().filter(q.lines()::contains).count();
        return 2 * common >= Math.min(p.lines().size(), q.lines().size());
    }

    private static long common(Path q, Set<String> names, Map<Long, StopRef> stops) {
        return names(q, stops).stream().filter(names::contains).count();
    }

    /** Whether two stretches run the same way: the second's first common stop comes before its last in the first. */
    private static boolean sameDirection(Path p, Path q, Map<Long, StopRef> stops) {
        List<String> a = p.stops().stream().map(s -> stops.get(s).name()).toList();
        List<String> b = q.stops().stream().map(s -> stops.get(s).name()).filter(a::contains).toList();
        return b.size() >= 2 && a.indexOf(b.get(0)) < a.indexOf(b.get(b.size() - 1));
    }

    private static Set<String> names(Path p, Map<Long, StopRef> stops) {
        Set<String> names = new HashSet<>();
        p.stops().forEach(s -> names.add(stops.get(s).name()));
        return names;
    }
}
