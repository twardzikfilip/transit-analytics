package com.transitanalytics.delays;

import com.transitanalytics.dto.DelayGenerationDto.Band;
import com.transitanalytics.dto.DelayGenerationDto.BrigadeGain;
import com.transitanalytics.dto.DelayGenerationDto.Direction;
import com.transitanalytics.dto.DelayGenerationDto.Example;
import com.transitanalytics.dto.DelayGenerationDto.ExampleStop;
import com.transitanalytics.dto.DelayGenerationDto.RouteStretch;
import com.transitanalytics.dto.DelayGenerationDto.RouteSummary;
import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.timetable.StopPlaces;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.transitanalytics.timetable.TimetableMath.ROUTE_ORDER;
import static com.transitanalytics.timetable.TimetableMath.percentile;
import static com.transitanalytics.timetable.TimetableMath.round;

/**
 * Delay generation from stop passages. A passage is a bus leaving a stop, with the feed's delay just after; passages
 * of one bus on one trip make a run, and the change in delay between two consecutive stops of a run is the delay
 * generated on that stretch (the drive, and the dwell at the second stop).
 */
public final class DelayGeneration {

    /** A run starts again after a gap this long without passages (missing data, or a break). */
    static final int MAX_GAP_SECONDS = 15 * 60;
    /** Runs with fewer passages are left out. */
    static final int MIN_PASSAGES = 3;
    /** Runs with fewer passages don't count for a route's gain from first stop to last. */
    static final int MIN_GAIN_PASSAGES = 5;
    /** Stretches measured fewer times are left off the map. */
    static final int MIN_STRETCH_RUNS = 5;
    /** Per-hour figures of a stretch need this many runs in the hour. */
    static final int MIN_HOUR_RUNS = 3;
    /** A run gaining this much from first stop to last counts as gaining (for a route's share). */
    static final int GAINING_RUN_SECONDS = 180;
    /** A stretch where a run gains this much counts as gaining there. */
    static final int GAINING_STRETCH_SECONDS = 60;
    /** Example runs per direction, and per stretch. */
    static final int DIRECTION_EXAMPLES = 8;
    static final int STRETCH_EXAMPLES = 3;
    /** A feed stop name is matched to a timetable stop of that name at most this far from the bus. */
    static final double MATCH_METERS = 1000;

    /** Parts of the day for the map, in local time; the first is the whole day. */
    static final List<Band> BANDS = List.of(new Band("all", "All day"),
            new Band("early", "Before 06:30"), new Band("am-peak", "Morning peak 06:30–08:30"),
            new Band("midday", "Midday 08:30–15:00"), new Band("pm-peak", "Afternoon peak 15:00–17:00"),
            new Band("evening", "Evening 17:00–20:00"), new Band("late", "After 20:00"));

    private DelayGeneration() {
    }

    /** A bus leaving a stop: the stop it passed (by the feed's name), when, its delay then and where it was. */
    record Passage(long vehicleId, String vehicle, String route, String brigade, String headsign, int seq,
                   String stop, Instant time, int delay, double lat, double lon) {
    }

    /** A stop by name and position. */
    public record Stop(String name, double lat, double lon) {
    }

    /** One bus on one trip: the stops it left in order, with the time and delay on leaving each. */
    public record Run(long vehicleId, String vehicle, String route, String brigade, String headsign, LocalDate day,
               Stop[] stops, int[] seqs, long[] times, int[] delays) {

        int size() {
            return stops.length;
        }

        /** Change in delay from the first stop to the last. */
        int gain() {
            return delays[size() - 1] - delays[0];
        }

        /** Whether passages i and i + 1 are consecutive stops, so the stretch between them was measured. */
        boolean measured(int i) {
            return seqs[i + 1] == seqs[i] + 1 && !stops[i].equals(stops[i + 1]);
        }
    }

    // ---- Stops ----------------------------------------------------------------------------------------------

    private static final Pattern CODE = Pattern.compile("[\\s\\u00A0]*\\(\\d+\\)[\\s\\u00A0]*$");
    private static final Pattern ON_REQUEST = Pattern.compile("[\\s\\u00A0]+NŻ[\\s\\u00A0]*$");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");

    /**
     * A stop name as both the feed and the timetable can be compared by: without the timetable's stop code and its
     * "NŻ" (stop on request), which the feed leaves out; spaces (also non-breaking) collapsed; lower case.
     */
    static String matchKey(String name) {
        String s = CODE.matcher(name).replaceFirst("");
        s = ON_REQUEST.matcher(s).replaceFirst("");
        return SPACES.matcher(s).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /** Finds a feed stop name's timetable stop: the one of that name nearest to where the bus was. */
    static final class StopResolver {
        private final Map<String, List<StopPlaces.StopRef>> byKey = new HashMap<>();
        private final Map<Stop, Stop> interned = new HashMap<>();

        StopResolver(List<StopPlaces.StopRef> stops) {
            for (StopPlaces.StopRef s : stops) {
                if (s.located()) {
                    byKey.computeIfAbsent(matchKey(s.name()), k -> new ArrayList<>()).add(s);
                }
            }
        }

        /** The stop, named as in the feed; where the bus was when no timetable stop of that name is near. */
        Stop resolve(String name, double lat, double lon) {
            String display = SPACES.matcher(name).replaceAll(" ").strip();
            StopPlaces.StopRef nearest = byKey.getOrDefault(matchKey(name), List.of()).stream()
                    .min(Comparator.comparingDouble(s -> StopPlaces.meters(lat, lon, s.lat(), s.lon())))
                    .filter(s -> StopPlaces.meters(lat, lon, s.lat(), s.lon()) <= MATCH_METERS)
                    .orElse(null);
            Stop stop = nearest == null ? new Stop(display, round(lat, 5), round(lon, 5))
                    : new Stop(display, nearest.lat(), nearest.lon());
            return interned.computeIfAbsent(stop, s -> s);
        }
    }

    // ---- Runs -----------------------------------------------------------------------------------------------

    /**
     * Passages (ordered by vehicle, then time) joined into runs: one bus, route, brigade and headsign, stops in
     * increasing order, without long gaps. Runs with fewer than {@link #MIN_PASSAGES} passages are left out.
     */
    static List<Run> runs(List<Passage> passages, StopResolver stops) {
        List<Run> runs = new ArrayList<>();
        List<Passage> current = new ArrayList<>();
        for (Passage p : passages) {
            if (!current.isEmpty() && !continues(current.get(current.size() - 1), p)) {
                addRun(runs, current, stops);
                current = new ArrayList<>();
            }
            current.add(p);
        }
        addRun(runs, current, stops);
        return runs;
    }

    private static boolean continues(Passage last, Passage next) {
        return last.vehicleId() == next.vehicleId() && last.route().equals(next.route())
                && java.util.Objects.equals(last.brigade(), next.brigade())
                && java.util.Objects.equals(last.headsign(), next.headsign())
                && next.seq() > last.seq()
                && next.time().getEpochSecond() - last.time().getEpochSecond() <= MAX_GAP_SECONDS;
    }

    private static void addRun(List<Run> runs, List<Passage> passages, StopResolver stops) {
        if (passages.size() < MIN_PASSAGES) {
            return;
        }
        int n = passages.size();
        Stop[] stopArray = new Stop[n];
        int[] seqs = new int[n];
        long[] times = new long[n];
        int[] delays = new int[n];
        for (int i = 0; i < n; i++) {
            Passage p = passages.get(i);
            stopArray[i] = stops.resolve(p.stop(), p.lat(), p.lon());
            seqs[i] = p.seq();
            times[i] = p.time().getEpochSecond();
            delays[i] = p.delay();
        }
        Passage first = passages.get(0);
        runs.add(new Run(first.vehicleId(), first.vehicle(), first.route(), first.brigade(), first.headsign(),
                ServiceDay.serviceDay(first.time()), stopArray, seqs, times, delays));
    }

    // ---- The network ----------------------------------------------------------------------------------------

    /** The band of a time (index in {@link #BANDS}, never 0: that is the whole day). */
    static int band(long epochSecond) {
        var local = Instant.ofEpochSecond(epochSecond).atZone(ServiceDay.ZONE);
        int minute = local.getHour() * 60 + local.getMinute();
        if (minute < 6 * 60 + 30) {
            return 1;
        }
        if (minute < 8 * 60 + 30) {
            return 2;
        }
        if (minute < 15 * 60) {
            return 3;
        }
        if (minute < 17 * 60) {
            return 4;
        }
        return minute < 20 * 60 ? 5 : 6;
    }

    /** Per band: runs and the sum of their changes in delay. */
    private static final class BandSums {
        final int[] runs = new int[BANDS.size()];
        final double[] sums = new double[BANDS.size()];
        final TreeSet<String> routes = new TreeSet<>(ROUTE_ORDER);

        void add(int band, int delta, String route) {
            runs[0]++;
            sums[0] += delta;
            runs[band]++;
            sums[band] += delta;
            routes.add(route);
        }
    }

    /** Every directed stretch measured at least {@link #MIN_STRETCH_RUNS} times, all routes together. */
    static List<DelayGenerationDto.Stretch> stretches(List<Run> runs) {
        Map<List<Stop>, BandSums> byStretch = new LinkedHashMap<>();
        for (Run r : runs) {
            for (int i = 0; i + 1 < r.size(); i++) {
                if (r.measured(i)) {
                    byStretch.computeIfAbsent(List.of(r.stops()[i], r.stops()[i + 1]), k -> new BandSums())
                            .add(band(r.times()[i]), r.delays()[i + 1] - r.delays()[i], r.route());
                }
            }
        }
        List<DelayGenerationDto.Stretch> stretches = new ArrayList<>();
        byStretch.forEach((key, sums) -> {
            if (sums.runs[0] < MIN_STRETCH_RUNS) {
                return;
            }
            List<Integer> counts = Arrays.stream(sums.runs).boxed().toList();
            List<Double> means = new ArrayList<>();
            for (int b = 0; b < BANDS.size(); b++) {
                means.add(sums.runs[b] == 0 ? null : round(sums.sums[b] / sums.runs[b], 1));
            }
            Stop a = key.get(0);
            Stop b = key.get(1);
            stretches.add(new DelayGenerationDto.Stretch(a.name(), b.name(), a.lat(), a.lon(), b.lat(), b.lon(),
                    List.copyOf(sums.routes), counts, Collections.unmodifiableList(means)));
        });
        return stretches;
    }

    /** Each route's gain per run, with its worst stretch; routes in route order. */
    static List<RouteSummary> routes(List<Run> runs, int days) {
        Map<String, List<Run>> byRoute = runs.stream().collect(Collectors.groupingBy(Run::route));
        List<RouteSummary> routes = new ArrayList<>();
        for (Map.Entry<String, List<Run>> e : byRoute.entrySet()) {
            int[] gains = e.getValue().stream().filter(r -> r.size() >= MIN_GAIN_PASSAGES).mapToInt(Run::gain)
                    .sorted().toArray();
            if (gains.length == 0) {
                continue;
            }
            double mean = Arrays.stream(gains).average().orElse(0);
            long gaining = Arrays.stream(gains).filter(g -> g >= GAINING_RUN_SECONDS).count();
            // The stretch where this route's buses gained the most delay altogether.
            DelayGenerationDto.Stretch worst = stretches(e.getValue()).stream()
                    .filter(s -> s.meanSeconds().get(0) > 0)
                    .max(Comparator.comparingDouble(s -> s.meanSeconds().get(0) * s.runs().get(0)))
                    .orElse(null);
            routes.add(new RouteSummary(e.getKey(), gains.length, round(mean, 1),
                    (int) percentile(Arrays.stream(gains).asDoubleStream().toArray(), 0.5),
                    round((double) gaining / gains.length, 2), round(mean * gains.length / days / 60, 1),
                    worst == null ? null : worst.from(), worst == null ? null : worst.to(),
                    worst == null ? null : worst.meanSeconds().get(0)));
        }
        routes.sort(Comparator.comparing(RouteSummary::route, ROUTE_ORDER));
        return routes;
    }

    // ---- One route -------------------------------------------------------------------------------------------

    /** One measurement of a stretch: the run, where in it, and the change in delay. */
    private record Leg(Run run, int at, int delta) {
    }

    /** A route's report: per headsign, its stretches in route order with hours and examples; its brigades. */
    static DelayGenerationDto.RouteReport routeReport(Instant from, Instant to, String route,
                                                    List<Run> routeRuns, int days) {
        Map<Run, Integer> exampleIds = new IdentityHashMap<>();
        Function<Run, Integer> idOf = r -> exampleIds.computeIfAbsent(r, k -> exampleIds.size() + 1);

        Map<String, List<Run>> byHeadsign = routeRuns.stream().collect(Collectors.groupingBy(
                r -> r.headsign() == null ? "" : r.headsign(), LinkedHashMap::new, Collectors.toList()));
        int minRuns = Math.max(MIN_PASSAGES, routeRuns.size() / 20);
        List<Direction> directions = new ArrayList<>();
        byHeadsign.entrySet().stream()
                .filter(e -> e.getValue().size() >= minRuns)
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Run>> e) -> e.getValue().size()).reversed())
                .forEach(e -> directions.add(direction(e.getKey(), e.getValue(), idOf)));

        List<BrigadeGain> brigades = routeRuns.stream().filter(r -> r.size() >= MIN_GAIN_PASSAGES)
                .collect(Collectors.groupingBy(r -> r.brigade() == null ? "—" : r.brigade()))
                .entrySet().stream()
                .map(e -> new BrigadeGain(e.getKey(), e.getValue().size(),
                        round(e.getValue().stream().mapToInt(Run::gain).average().orElse(0), 1),
                        e.getValue().stream().mapToInt(Run::gain).max().orElse(0)))
                .sorted(Comparator.comparingDouble(BrigadeGain::meanGainSeconds).reversed())
                .toList();

        List<Example> examples = exampleIds.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(e -> example(e.getValue(), e.getKey()))
                .toList();
        return new DelayGenerationDto.RouteReport(from, to, route, days, directions, brigades, examples);
    }

    private static Direction direction(String headsign, List<Run> runs, Function<Run, Integer> idOf) {
        Map<List<Stop>, List<Leg>> legs = new LinkedHashMap<>();
        for (Run r : runs) {
            for (int i = 0; i + 1 < r.size(); i++) {
                if (r.measured(i)) {
                    legs.computeIfAbsent(List.of(r.stops()[i], r.stops()[i + 1]), k -> new ArrayList<>())
                            .add(new Leg(r, i, r.delays()[i + 1] - r.delays()[i]));
                }
            }
        }
        List<RouteStretch> stretches = new ArrayList<>();
        legs.forEach((key, list) -> {
            if (list.size() < MIN_PASSAGES) {
                return;
            }
            double[] deltas = list.stream().mapToDouble(Leg::delta).sorted().toArray();
            double[] hourSums = new double[24];
            int[] hourRuns = new int[24];
            for (Leg l : list) {
                int hour = Instant.ofEpochSecond(l.run().times()[l.at()]).atZone(ServiceDay.ZONE).getHour();
                hourSums[hour] += l.delta();
                hourRuns[hour]++;
            }
            List<Double> byHour = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                byHour.add(hourRuns[h] < MIN_HOUR_RUNS ? null : round(hourSums[h] / hourRuns[h], 1));
            }
            int[] seqs = list.stream().mapToInt(l -> l.run().seqs()[l.at()]).sorted().toArray();
            List<Integer> examples = list.stream().filter(l -> l.delta() >= GAINING_STRETCH_SECONDS)
                    .sorted(Comparator.comparingInt(Leg::delta).reversed()).limit(STRETCH_EXAMPLES)
                    .map(l -> idOf.apply(l.run())).toList();
            stretches.add(new RouteStretch(key.get(0).name(), key.get(1).name(), seqs[seqs.length / 2], list.size(),
                    round(Arrays.stream(deltas).average().orElse(0), 1),
                    (int) percentile(deltas, 0.5),
                    (int) percentile(deltas, 0.9),
                    round((double) list.stream().filter(l -> l.delta() >= GAINING_STRETCH_SECONDS).count()
                            / list.size(), 2),
                    round(list.stream().mapToInt(l -> l.run().delays()[l.at()]).average().orElse(0), 1),
                    Collections.unmodifiableList(byHour), examples));
        });
        stretches.sort(Comparator.comparingInt(RouteStretch::fromSeq).thenComparing(RouteStretch::from));

        List<Run> full = runs.stream().filter(r -> r.size() >= MIN_GAIN_PASSAGES).toList();
        List<Integer> examples = full.stream().sorted(Comparator.comparingInt(Run::gain).reversed())
                .limit(DIRECTION_EXAMPLES).map(idOf).toList();
        return new Direction(headsign, runs.size(),
                round(full.stream().mapToInt(Run::gain).average().orElse(0), 1), stretches, examples);
    }

    private static Example example(int id, Run r) {
        List<ExampleStop> stops = new ArrayList<>();
        for (int i = 0; i < r.size(); i++) {
            stops.add(new ExampleStop(r.stops()[i].name(), r.seqs()[i], Instant.ofEpochSecond(r.times()[i]),
                    r.delays()[i]));
        }
        return new Example(id, r.day(), r.vehicle(), r.brigade(), r.headsign(), r.gain(), stops);
    }
}
