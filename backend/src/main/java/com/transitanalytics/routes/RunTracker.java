package com.transitanalytics.routes;

import com.transitanalytics.data.Reports;
import com.transitanalytics.dto.RouteDetailDto.HourStat;
import com.transitanalytics.dto.RouteDetailDto.StopPoint;
import com.transitanalytics.dto.RouteDetailDto.Variant;
import com.transitanalytics.dto.RouteDetailDto;
import com.transitanalytics.timetable.TimetableMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.transitanalytics.routes.RouteTimetable.*;
import static com.transitanalytics.timetable.Periods.*;
import static com.transitanalytics.timetable.ServiceDay.*;
import static com.transitanalytics.timetable.StopPlaces.*;
import static com.transitanalytics.timetable.TimetableMath.*;

/**
 * Runs measured from recorded positions: reports split into runs, the time each stop was passed, which variant a run
 * covered end to end, and travel times, speeds and stop-to-stop speeds from those runs.
 */
public final class RunTracker {

    /** Positions of one vehicle further apart than this don't belong to the same run. */
    static final long MAX_POLL_GAP_SECONDS = Reports.PAIR_GAP.toSeconds();
    /** Half the polling interval: how long after its last report a bus is assumed to arrive when no report follows. */
    static final int HALF_POLL_SECONDS = 30;

    /**
     * One position report: time in epoch seconds, seq = index of the next stop (0 = waiting at the first stop) and
     * that stop's name.
     */
    record Sample(long vehicle, double t, String brigade, String headsign, int seq, String nextStop) {
        Sample(long vehicle, double t, String brigade, String headsign, int seq) {
            this(vehicle, t, brigade, headsign, seq, null);
        }
    }

    /**
     * Consecutive reports of one bus on one run.
     *
     * @param after time of the bus's next report after the run, null when there is none within a few minutes
     */
    record Track(String headsign, List<Sample> samples, Double after) {
    }

    /** A run recorded from its first to its last stop; times[k] = when the bus left stop k. */
    record FullRun(int direction, double[] times, Variant variant) {
        FullRun(int direction, double[] times) {
            this(direction, times, null);
        }

        double departure() {
            return times[0];
        }

        double minutes() {
            return (times[times.length - 1] - times[0]) / 60;
        }
    }

    /**
     * The variant a recorded run covered from end to end, with its pass times, or null. The run must have the
     * variant's headsign, cover all its stops, and take between half the shortest and twice the longest scheduled
     * time. When several variants fit (they share a headsign), one whose last stop the run reached wins, then one
     * whose stop name matches the last reported next stop.
     */
    static FullRun matchRun(Track track, List<Variant> variants) {
        List<FullRun> fits = new ArrayList<>();
        for (Variant v : variants) {
            if (!Objects.equals(track.headsign(), v.headsign())) {
                continue;
            }
            double[] times = passTimes(track, kms(v));
            if (times == null) {
                continue;
            }
            double seconds = times[times.length - 1] - times[0];
            if (seconds >= v.shortestMin() * 60 * 0.5 && seconds <= v.longestMin() * 60 * 2.0) {
                fits.add(new FullRun(v.direction(), times, v));
            }
        }
        if (fits.size() <= 1) {
            return fits.isEmpty() ? null : fits.get(0);
        }
        // Prefer a variant the run reached the last stop of (not just the last but one), then one whose stop at the
        // last report's index has the reported name.
        Sample last = track.samples().get(track.samples().size() - 1);
        int highest = track.samples().stream().mapToInt(Sample::seq).max().orElse(0);
        return fits.stream().min(Comparator
                .comparing((FullRun r) -> highest != r.variant().stops().size() - 1)
                .thenComparing(r -> {
                    List<StopPoint> stops = r.variant().stops();
                    return !(last.seq() < stops.size() && last.nextStop() != null
                            && stopName(last.nextStop()).equalsIgnoreCase(stops.get(last.seq()).name()));
                })).orElseThrow();
    }

    /** Patterns with fewer scheduled trips a day than this (both directions) are too rare to chart. */
    static final int MIN_PATTERN_TRIPS = 3;

    /**
     * Route patterns to measure: each base route and longer variant together with the variants running between the
     * same two ends (in either direction). Base-route patterns come first, then by trips a day. A short turn or a
     * depot run only joins a pattern; it never starts one.
     */
    static List<List<Variant>> patterns(List<Variant> variants) {
        List<List<Variant>> patterns = new ArrayList<>();
        List<Variant> seeds = variants.stream().filter(v -> v.kind().equals("main") || v.kind().equals("longer"))
                .toList();
        for (Variant v : seeds) {
            patterns.stream().filter(p -> sameEnds(p.get(0), v)).findFirst()
                    .ifPresentOrElse(p -> p.add(v), () -> patterns.add(new ArrayList<>(List.of(v))));
        }
        for (Variant v : variants) {
            if (v.kind().equals("shorter")) {
                patterns.stream().filter(p -> sameEnds(p.get(0), v)).findFirst().ifPresent(p -> p.add(v));
            }
        }
        return patterns.stream()
                .filter(p -> p.stream().mapToInt(Variant::tripsPerDay).sum() >= MIN_PATTERN_TRIPS)
                .sorted(Comparator.comparing((List<Variant> p) -> p.stream().noneMatch(v -> v.kind().equals("main")))
                        .thenComparing(p -> -p.stream().mapToInt(Variant::tripsPerDay).sum()))
                .toList();
    }

    /** Whether two variants run between the same two ends, in either direction. */
    static boolean sameEnds(Variant a, Variant b) {
        StopRef a0 = end(a, true), a1 = end(a, false), b0 = end(b, true), b1 = end(b, false);
        return (samePlace(a0, b0) && samePlace(a1, b1)) || (samePlace(a0, b1) && samePlace(a1, b0));
    }

    /** A variant's first or last stop, with its position when the variant has its stops. */
    static StopRef end(Variant v, boolean first) {
        if (v.stops().isEmpty()) {
            return new StopRef(first ? v.firstStop() : v.lastStop(), null, null);
        }
        StopPoint s = v.stops().get(first ? 0 : v.stops().size() - 1);
        return new StopRef(s.name(), s.lat(), s.lon());
    }

    /** Travel time and speed by departure hour for the full runs of one route pattern, with its scheduled range. */
    static RouteDetailDto.TravelTimes travelTimes(String kind, String label, List<Variant> variants,
                                                 List<TripRow> trips, List<FullRun> runs) {
        Map<Integer, List<Double>> minutes = new TreeMap<>();
        Map<Integer, List<Double>> speeds = new TreeMap<>();
        for (FullRun run : runs) {
            int hour = serviceHour(run.departure());
            minutes.computeIfAbsent(hour, h -> new ArrayList<>()).add(run.minutes());
            speeds.computeIfAbsent(hour, h -> new ArrayList<>()).add(run.variant().km() / (run.minutes() / 60));
        }
        List<Double> scheduledSpeeds = trips.stream().filter(t -> t.km() != null && t.duration() > 0)
                .map(t -> t.km() / (t.duration() / 3600.0)).toList();
        return new RouteDetailDto.TravelTimes(kind, label,
                TimetableMath.round(variants.stream().mapToDouble(Variant::km).average().orElse(0), 1), runs.size(),
                trips.stream().map(t -> t.duration() / 60).min(Integer::compare).orElse(null),
                trips.stream().map(t -> t.duration() / 60).max(Integer::compare).orElse(null),
                scheduledSpeeds.stream().min(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                scheduledSpeeds.stream().max(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                minutes.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 0)).toList(),
                speeds.entrySet().stream().map(e -> hourStat(e.getKey(), e.getValue(), 1)).toList());
    }

    /**
     * Splits reports (ordered by vehicle and time) into runs: a run ends when the bus's brigade or headsign changes,
     * its next stop goes back by more than two stops, or it doesn't report for {@link #MAX_POLL_GAP_SECONDS}. At a
     * terminus the feed switches to the next run's headsign, so the layover belongs to the next run.
     */
    static List<Track> splitRuns(List<Sample> samples) {
        List<Track> tracks = new ArrayList<>();
        List<Sample> current = new ArrayList<>();
        for (Sample s : samples) {
            if (!current.isEmpty()) {
                Sample last = current.get(current.size() - 1);
                boolean sameBus = last.vehicle() == s.vehicle() && s.t() - last.t() <= MAX_POLL_GAP_SECONDS;
                int highest = current.stream().mapToInt(Sample::seq).max().orElse(0);
                if (sameBus && Objects.equals(last.brigade(), s.brigade())
                        && Objects.equals(last.headsign(), s.headsign()) && s.seq() >= highest - 2) {
                    current.add(s);
                    continue;
                }
                tracks.add(new Track(current.get(0).headsign(), current, sameBus ? s.t() : null));
                current = new ArrayList<>();
            }
            current.add(s);
        }
        if (!current.isEmpty()) {
            tracks.add(new Track(current.get(0).headsign(), current, null));
        }
        return tracks;
    }

    /**
     * When the bus left each stop of a run (epoch seconds; the last entry is the arrival at the terminus), or null
     * unless the run covers the whole route: it starts waiting at the first stop and reaches the last one, no further
     * (or the last-but-one, when the bus's next report follows).
     *
     * <p>A report gives the next stop, so stops passed between two reports were passed somewhere in between: a single
     * stop at the midpoint (±30 s with one report a minute), several spread by their distance along the route. The
     * terminus is reached between the run's last report and the bus's next one, or half a poll after the last report
     * when none follows.
     *
     * @param km each stop's distance along the route
     */
    static double[] passTimes(Track track, double[] km) {
        int n = km.length;
        List<Sample> samples = track.samples();
        if (n < 2 || samples.isEmpty() || samples.get(0).seq() != 0) {
            return null;
        }
        // The feed often switches to the next run's headsign before it reports the terminus as the next stop, so a run
        // heading to the last-but-one stop counts too when the bus's next run follows straight after.
        int highest = samples.stream().mapToInt(Sample::seq).max().orElse(0);
        if (highest > n - 1 || highest < n - 2 || (highest == n - 2 && track.after() == null)) {
            return null;
        }
        double[] times = new double[n];
        int passed = 0; // stops [0, passed) have a time
        for (int i = 1; i < samples.size(); i++) {
            int next = Math.min(samples.get(i).seq(), n - 1);
            if (next > passed) {
                spread(times, km, passed, next, samples.get(i - 1).t(), samples.get(i).t());
                passed = next;
            }
        }
        double last = samples.get(samples.size() - 1).t();
        double end = track.after() != null ? track.after() : last + 2 * HALF_POLL_SECONDS;
        spread(times, km, passed, n, last, end);
        return times;
    }

    /** Places stops [from, to) between t0 and t1: one at the midpoint, several spread by distance. */
    static void spread(double[] times, double[] km, int from, int to, double t0, double t1) {
        int count = to - from;
        double span = km[to - 1] - km[from];
        if (count == 1 || span <= 0) {
            for (int j = from; j < to; j++) {
                times[j] = t0 + (t1 - t0) * (j - from + 0.5) / count;
            }
            return;
        }
        // Half the average stop spacing before the first and after the last stop, so the stops sit inside the gap.
        double pad = span / (count - 1);
        for (int j = from; j < to; j++) {
            times[j] = t0 + (t1 - t0) * (km[j] - km[from] + pad / 2) / (span + pad);
        }
    }

    /** Min, max, mean and median of the values, rounded to the decimals. */
    static HourStat hourStat(int hour, List<Double> values, int decimals) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
        return new HourStat(hour, n, TimetableMath.round(sorted.get(0), decimals),
                TimetableMath.round(sorted.get(n - 1), decimals), TimetableMath.round(mean, decimals),
                TimetableMath.round(median, decimals));
    }

    /**
     * Speed between consecutive stops of a route: total distance over total time of the runs, so the ±30 s error of
     * each pass time averages out over many runs.
     */
    static RouteDetailDto.SegmentProfile segmentProfile(Variant main, List<FullRun> runs) {
        List<StopPoint> stops = main.stops();
        List<RouteDetailDto.Segment> segments = new ArrayList<>();
        for (int k = 0; k + 1 < stops.size(); k++) {
            double km = stops.get(k + 1).km() - stops.get(k).km();
            double seconds = 0;
            for (FullRun run : runs) {
                seconds += run.times()[k + 1] - run.times()[k];
            }
            Double kmh = runs.isEmpty() || seconds <= 0 ? null
                    : TimetableMath.round(km * runs.size() / (seconds / 3600), 1);
            segments.add(new RouteDetailDto.Segment(stops.get(k).name(), stops.get(k + 1).name(),
                    stops.get(k).km(), stops.get(k + 1).km(), kmh));
        }
        return new RouteDetailDto.SegmentProfile(main.direction(), main.headsign(), runs.size(), segments);
    }

    static double[] kms(Variant v) {
        return v.stops().stream().mapToDouble(StopPoint::km).toArray();
    }
}
