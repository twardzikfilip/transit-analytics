package com.transitanalytics.service;

import com.transitanalytics.buslanes.Geo;
import com.transitanalytics.dto.LineDetailDto;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a line's buses actually go, from their recorded positions (for lines without a timetable, e.g. regional
 * ones). Each bus's reports are split into runs: the same destination, without a gap of over {@link #MAX_GAP}. Runs
 * to the same destination along the same streets form a variant: a run belongs to one when nearly all of it
 * ({@link #SHARE_ON_PATH}) is within {@link #NEAR_METERS} of the variant's path. A variant's path is that of its
 * first run; runs are taken longest first (a run that starts or ends part-way then joins the full one), except
 * that runs far longer than usual for their destination (a detour, a trip to the depot) come last, so they don't
 * become the path others are measured against.
 */
final class RecordedRoutes {

    static final Duration MAX_GAP = Duration.ofMinutes(10);
    static final double NEAR_METERS = 150;
    static final double SHARE_ON_PATH = 0.85;
    /** Runs shorter than this, or with fewer reports, are a bus standing or just passing: left out. */
    static final double MIN_RUN_METERS = 1000;
    static final int MIN_REPORTS = 4;
    /** Longer than this many times the usual (median) run to the same destination: not a seed for a variant. */
    static final double UNUSUAL_LENGTH = 1.5;
    /** A variant driven once and shorter than this is noise (a bus standing, a GPS jump): left out. */
    static final double MIN_ONE_OFF_METERS = 3000;
    /** Paths are simplified to within this many metres of the reports. */
    static final double SIMPLIFY_METERS = 10;

    /** One report. */
    record Report(long vehicle, Instant time, double lat, double lon, String headsign, String nextStop) {
    }

    /** A bus's reports in a row towards one destination. */
    record Run(String headsign, List<Report> reports, double meters) {

        List<double[]> points() {
            return reports.stream().map(r -> new double[] {r.lat(), r.lon()}).toList();
        }
    }

    private RecordedRoutes() {
    }

    /** The variants from a line's reports (in any order), the most driven first. */
    static LineDetailDto.Recorded recorded(List<Report> reports) {
        List<Run> runs = runs(reports);
        if (runs.isEmpty()) {
            return new LineDetailDto.Recorded(null, null, 0, List.of());
        }
        Map<String, Double> usual = runs.stream().collect(Collectors.groupingBy(Run::headsign,
                Collectors.collectingAndThen(Collectors.toList(), RecordedRoutes::medianMeters)));
        List<Run> ordered = new ArrayList<>(runs);
        ordered.sort(Comparator.comparing((Run r) -> r.meters() > UNUSUAL_LENGTH * usual.get(r.headsign()))
                .thenComparing(Comparator.comparingDouble(Run::meters).reversed()));

        record Group(Run seed, List<double[]> path, List<Run> runs) {
        }
        List<Group> groups = new ArrayList<>();
        for (Run run : ordered) {
            Group match = groups.stream()
                    .filter(g -> g.seed().headsign().equals(run.headsign()) && onPath(run.points(), g.path()))
                    .findFirst().orElse(null);
            if (match == null) {
                groups.add(new Group(run, run.points(), new ArrayList<>(List.of(run))));
            } else {
                match.runs().add(run);
            }
        }
        groups.removeIf(g -> g.runs().size() == 1 && g.seed().meters() < MIN_ONE_OFF_METERS);
        groups.sort(Comparator.comparingInt((Group g) -> -g.runs().size()));
        List<LineDetailDto.RecordedVariant> variants = new ArrayList<>();
        for (Group g : groups) {
            List<Run> mine = g.runs();
            variants.add(new LineDetailDto.RecordedVariant(variants.size() + 1, g.seed().headsign(),
                    mostCommon(mine, r -> r.reports().get(0).nextStop()),
                    mostCommon(mine, r -> r.reports().get(r.reports().size() - 1).nextStop()),
                    mine.size(), TimetableMath.round(g.seed().meters() / 1000, 1),
                    mine.stream().map(r -> r.reports().get(r.reports().size() - 1).time()).max(Instant::compareTo)
                            .orElseThrow(),
                    simplify(g.path(), SIMPLIFY_METERS)));
        }
        Instant first = runs.stream().map(r -> r.reports().get(0).time()).min(Instant::compareTo).orElseThrow();
        Instant last = runs.stream().map(r -> r.reports().get(r.reports().size() - 1).time())
                .max(Instant::compareTo).orElseThrow();
        return new LineDetailDto.Recorded(first, last, runs.size(), variants);
    }

    /** Each bus's reports split into runs (same destination, no long gap); standing reports kept once. */
    static List<Run> runs(List<Report> reports) {
        List<Report> sorted = new ArrayList<>(reports);
        sorted.sort(Comparator.comparingLong(Report::vehicle).thenComparing(Report::time));
        List<Run> runs = new ArrayList<>();
        List<Report> current = new ArrayList<>();
        Report previous = null;
        for (Report r : sorted) {
            boolean breaks = previous == null || previous.vehicle() != r.vehicle()
                    || !String.valueOf(previous.headsign()).equals(String.valueOf(r.headsign()))
                    || Duration.between(previous.time(), r.time()).compareTo(MAX_GAP) > 0;
            if (breaks) {
                close(current, runs);
                current = new ArrayList<>();
            }
            if (current.isEmpty() || meters(current.get(current.size() - 1), r) > 5) {
                current.add(r);
            }
            previous = r;
        }
        close(current, runs);
        return runs;
    }

    private static void close(List<Report> reports, List<Run> runs) {
        if (reports.size() < MIN_REPORTS || reports.get(0).headsign() == null) {
            return;
        }
        double length = 0;
        for (int i = 1; i < reports.size(); i++) {
            length += meters(reports.get(i - 1), reports.get(i));
        }
        if (length >= MIN_RUN_METERS) {
            runs.add(new Run(reports.get(0).headsign(), List.copyOf(reports), length));
        }
    }

    /** Whether nearly all of {@code points} lies along {@code path}. */
    static boolean onPath(List<double[]> points, List<double[]> path) {
        int near = 0;
        for (double[] p : points) {
            if (distance(p, path) <= NEAR_METERS) {
                near++;
            }
        }
        return near >= SHARE_ON_PATH * points.size();
    }

    /** Metres from a point to a polyline ([lat, lon]). */
    static double distance(double[] p, List<double[]> path) {
        double[] q = Geo.xy(p[0], p[1]);
        double best = Double.MAX_VALUE;
        double[] a = Geo.xy(path.get(0)[0], path.get(0)[1]);
        if (path.size() == 1) {
            return Math.hypot(q[0] - a[0], q[1] - a[1]);
        }
        for (int i = 1; i < path.size(); i++) {
            double[] b = Geo.xy(path.get(i)[0], path.get(i)[1]);
            best = Math.min(best, toSegment(q, a, b));
            a = b;
        }
        return best;
    }

    /** Douglas–Peucker: the fewest points keeping the line within {@code tolerance} metres of every original one. */
    static List<double[]> simplify(List<double[]> points, double tolerance) {
        if (points.size() < 3) {
            return points;
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        List<double[]> xy = points.stream().map(p -> Geo.xy(p[0], p[1])).toList();
        java.util.ArrayDeque<int[]> spans = new java.util.ArrayDeque<>();
        spans.push(new int[] {0, points.size() - 1});
        while (!spans.isEmpty()) {
            int[] s = spans.pop();
            double far = 0;
            int at = -1;
            for (int i = s[0] + 1; i < s[1]; i++) {
                double d = toSegment(xy.get(i), xy.get(s[0]), xy.get(s[1]));
                if (d > far) {
                    far = d;
                    at = i;
                }
            }
            if (at >= 0 && far > tolerance) {
                keep[at] = true;
                spans.push(new int[] {s[0], at});
                spans.push(new int[] {at, s[1]});
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    private static double toSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len));
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
    }

    private static double meters(Report a, Report b) {
        return Geo.meters(new double[] {a.lat(), a.lon()}, new double[] {b.lat(), b.lon()});
    }

    private static double medianMeters(List<Run> runs) {
        double[] m = runs.stream().mapToDouble(Run::meters).sorted().toArray();
        return m[m.length / 2];
    }

    private static String mostCommon(List<Run> runs, Function<Run, String> name) {
        Map<String, Integer> counts = new HashMap<>();
        runs.stream().map(name).filter(java.util.Objects::nonNull).forEach(n -> counts.merge(n, 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey).orElse(null);
    }
}
