package com.transitanalytics.service;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.recording.DelayOutliers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Judges a service day's scheduled trips against the recorded positions. Each report is matched to a trip of its
 * brigade by scheduled time (the report's time minus its delay) and line, preferring the same headsign; a trip's
 * reports tell which of its stops the bus was heading to, and how far it was from the trip's route shape.
 */
final class TripAnomalies {

    /** A report matches a trip scheduled to leave its next stop at most this far from the report's scheduled time. */
    static final Duration MATCH_TOLERANCE = Duration.ofMinutes(20);
    /** A run counts as started if first seen heading to one of the trip's first stops (reports come every 20 s). */
    static final int START_STOPS = 3;
    /** …and as finished if last seen heading to one of its last two stops. */
    static final int END_STOPS = 2;
    /** Reports further than this from the route shape are off the route… */
    static final double OFF_ROUTE_METERS = 150;
    /** …and this many in a row (about a minute) make a detour; GPS errors don't last that long. */
    static final int DETOUR_REPORTS = 3;
    /**
     * A trip not seen at all is judged once this long after its scheduled arrival (a late bus may still run it);
     * until then, from {@link #DUE} after its departure, it is missing "so far".
     */
    static final Duration SETTLE = Duration.ofMinutes(15);
    /** A trip not seen this long after its scheduled departure counts as not run (so far). */
    static final Duration DUE = Duration.ofMinutes(10);
    /** A bus not seen for this long after leaving a trip, while recording went on, is lost. */
    static final Duration LOST_AFTER = Duration.ofMinutes(15);
    /** This many minutes in a row without any report is a gap in recording. */
    static final int GAP_MINUTES = 3;
    /** Two reports of a detour are consecutive when at most this far apart. */
    private static final Duration DETOUR_REPORT_GAP = Duration.ofSeconds(90);

    private TripAnomalies() {
    }

    enum Kind {
        COMPLETED("completed"), DETOUR("detour"), NO_SIGNAL("no-signal"), SHORT_TURNED("short-turned"),
        LOST("lost"), MISSED("missed"), CANCELLED("cancelled"), NOT_RECORDED("not-recorded"), PENDING("pending");

        final String key;

        Kind(String key) {
            this.key = key;
        }

        /** Whether the trip was judged (recorded and over, or already found abnormal). */
        boolean judged() {
            return this != NOT_RECORDED && this != PENDING;
        }

        boolean abnormal() {
            return judged() && this != COMPLETED;
        }
    }

    /**
     * A trip of the timetable on a service day: its stops (sequence numbers, names, and scheduled departures in
     * seconds after {@code departure}).
     */
    record ScheduledTrip(String tripId, String line, String brigade, String headsign, Instant departure,
                         Instant arrival, int[] seqs, String[] stops, int[] offsets, String shapeId) {

        int size() {
            return seqs.length;
        }

        /** The position (0-based) of a stop sequence number on the trip; the nearest one when it isn't on it. */
        int index(int seq) {
            int i = Arrays.binarySearch(seqs, seq);
            return i >= 0 ? i : Math.min(seqs.length - 1, Math.max(0, -i - 1));
        }

        /** When the bus is scheduled to leave the stop with this sequence number. */
        Instant scheduledAt(int seq) {
            return departure.plusSeconds(offsets[index(seq)]);
        }
    }

    /** A recorded position: who, when, on which line and brigade, its delay and the next stop's sequence number. */
    record Report(long vehicleId, String vehicle, Instant time, String line, String brigade, String headsign,
                  Integer delay, Integer nextSeq, double lat, double lon) {

        /** The scheduled time the bus was running to: the report's time minus its delay (outliers ignored). */
        Instant scheduled() {
            return delay == null || Math.abs(delay) > DelayOutliers.THRESHOLD_SECONDS ? time
                    : time.minusSeconds(delay);
        }

        /** The delay, unless it is an outlier (a feed error). */
        Integer usableDelay() {
            return delay == null || Math.abs(delay) > DelayOutliers.THRESHOLD_SECONDS ? null : delay;
        }
    }

    /** A judged trip: its line, scheduled run and outcome, and the details when it is abnormal. */
    record Outcome(String line, Instant departure, Instant arrival, Kind kind, TripAnomalyDto.Trip trip) {
    }

    private static Outcome outcome(ScheduledTrip trip, Kind kind) {
        return new Outcome(trip.line(), trip.departure(), trip.arrival(), kind, null);
    }

    // ---- Recording ------------------------------------------------------------------------------------------

    /** The minutes with at least one report; a run of {@link #GAP_MINUTES} without any is a gap. */
    static final class Recording {
        private final Set<Long> minutes;

        Recording(Set<Long> minutes) {
            this.minutes = minutes;
        }

        static Recording of(List<Report> reports) {
            return new Recording(reports.stream().map(r -> r.time().getEpochSecond() / 60)
                    .collect(Collectors.toCollection(HashSet::new)));
        }

        /** Whether recording went on throughout {@code [from, to]}: no gap of {@link #GAP_MINUTES} or more. */
        boolean covers(Instant from, Instant to) {
            int missing = 0;
            for (long m = from.getEpochSecond() / 60; m <= to.getEpochSecond() / 60; m++) {
                missing = minutes.contains(m) ? 0 : missing + 1;
                if (missing >= GAP_MINUTES) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- Route shapes ---------------------------------------------------------------------------------------

    private static final double M_PER_DEG = 111_320;
    private static final double COS_LAT = Math.cos(Math.toRadians(53.13));

    /** A route shape in metres (a flat projection, fine across a city). */
    record Shape(double[] x, double[] y) {
    }

    /** A shape from its points, {lat, lon} in order. */
    static Shape shape(List<double[]> points) {
        double[] x = new double[points.size()];
        double[] y = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            x[i] = points.get(i)[1] * M_PER_DEG * COS_LAT;
            y[i] = points.get(i)[0] * M_PER_DEG;
        }
        return new Shape(x, y);
    }

    /** Distance in metres from a point to the nearest part of a shape. */
    static double distance(Shape shape, double lat, double lon) {
        return new Tracker(shape).distance(lat, lon);
    }

    /**
     * Distances from a bus's successive positions to a shape. A bus moves along its route, so the segments near the
     * last nearest one are tried first; the whole shape only when the bus seems off it.
     */
    static final class Tracker {
        /** Segments either side of the last nearest one tried first. */
        private static final int NEAR = 25;
        private final Shape shape;
        private int last = -1;

        Tracker(Shape shape) {
            this.shape = shape;
        }

        double distance(double lat, double lon) {
            double px = lon * M_PER_DEG * COS_LAT;
            double py = lat * M_PER_DEG;
            int segments = shape.x().length - 1;
            if (segments < 1) {
                return Math.sqrt(squared(px - shape.x()[0], py - shape.y()[0]));
            }
            if (last >= 0) {
                double near = nearest(px, py, Math.max(0, last - NEAR), Math.min(segments, last + NEAR + 1));
                if (near <= OFF_ROUTE_METERS * OFF_ROUTE_METERS) {
                    return Math.sqrt(near);
                }
            }
            return Math.sqrt(nearest(px, py, 0, segments));
        }

        /** The squared distance to the nearest of segments {@code [from, to)}; remembers which one it was. */
        private double nearest(double px, double py, int from, int to) {
            double[] x = shape.x();
            double[] y = shape.y();
            double best = Double.MAX_VALUE;
            for (int i = from; i < to; i++) {
                double dx = x[i + 1] - x[i];
                double dy = y[i + 1] - y[i];
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x[i]) * dx + (py - y[i]) * dy) / len2));
                double d = squared(px - (x[i] + t * dx), py - (y[i] + t * dy));
                if (d < best) {
                    best = d;
                    last = i;
                }
            }
            return best;
        }

        private static double squared(double dx, double dy) {
            return dx * dx + dy * dy;
        }
    }

    /** The longest detour of a run: reports off the route, in a row, while between its first and last stops. */
    record Detour(int meters, int minutes, String from, String to) {
    }

    static Detour detour(ScheduledTrip trip, List<Report> reports, Shape shape) {
        if (shape == null) {
            return null;
        }
        Detour longest = null;
        int longestLength = 0;
        List<Report> run = new ArrayList<>();
        double runMax = 0;
        Report previous = null;
        Tracker tracker = new Tracker(shape);
        for (Report r : reports) {
            boolean between = r.nextSeq() != null && trip.index(r.nextSeq()) >= 1
                    && trip.index(r.nextSeq()) <= trip.size() - 2;
            double d = between ? tracker.distance(r.lat(), r.lon()) : 0;
            boolean continues = previous != null && previous.vehicleId() == r.vehicleId()
                    && Duration.between(previous.time(), r.time()).compareTo(DETOUR_REPORT_GAP) <= 0;
            if (d > OFF_ROUTE_METERS && (run.isEmpty() || continues)) {
                run.add(r);
                runMax = Math.max(runMax, d);
            } else {
                if (run.size() >= DETOUR_REPORTS && run.size() > longestLength) {
                    longest = detourOf(trip, run, runMax);
                    longestLength = run.size();
                }
                run = new ArrayList<>();
                runMax = 0;
                if (d > OFF_ROUTE_METERS) {
                    run.add(r);
                    runMax = d;
                }
            }
            previous = r;
        }
        if (run.size() >= DETOUR_REPORTS && run.size() > longestLength) {
            longest = detourOf(trip, run, runMax);
        }
        return longest;
    }

    private static Detour detourOf(ScheduledTrip trip, List<Report> run, double maxMeters) {
        int start = trip.index(run.get(0).nextSeq());
        int end = trip.index(run.get(run.size() - 1).nextSeq());
        long seconds = Duration.between(run.get(0).time(), run.get(run.size() - 1).time()).toSeconds() + 20;
        return new Detour((int) Math.round(maxMeters), (int) Math.max(1, Math.round(seconds / 60.0)),
                trip.stops()[Math.max(0, start - 1)], trip.stops()[end]);
    }

    // ---- Judging --------------------------------------------------------------------------------------------

    /**
     * Every scheduled trip of a service day, judged against that day's reports.
     *
     * @param reports the day's reports on the timetable's lines, ordered by vehicle and time
     * @param shapes  route shapes by id
     * @param now     trips not over by then are pending (or already abnormal)
     */
    static List<Outcome> judge(List<ScheduledTrip> trips, List<Report> reports, Recording recording,
                               Map<String, Shape> shapes, Instant now) {
        Map<String, List<ScheduledTrip>> byBrigade = trips.stream().filter(t -> t.brigade() != null)
                .collect(Collectors.groupingBy(ScheduledTrip::brigade));
        byBrigade.values().forEach(list -> list.sort(Comparator.comparing(ScheduledTrip::departure)));

        Map<ScheduledTrip, List<Report>> matched = new IdentityHashMap<>();
        Map<Long, List<Report>> byVehicle = new HashMap<>();
        Set<String> linesReported = new HashSet<>();
        for (Report r : withBrigades(reports)) {
            byVehicle.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>()).add(r);
            linesReported.add(r.line());
            ScheduledTrip trip = match(r, byBrigade.get(r.brigade()));
            if (trip != null) {
                matched.computeIfAbsent(trip, k -> new ArrayList<>()).add(r);
            }
        }
        Set<String> linesMatched = matched.keySet().stream().map(ScheduledTrip::line).collect(Collectors.toSet());

        Context context = new Context(byBrigade, matched, byVehicle, recording, shapes, now, linesReported,
                linesMatched);
        return trips.stream().map(context::judge).toList();
    }

    /** A report without a brigade takes it from the bus's report next to it, on the same line, this close in time. */
    static final Duration BRIGADE_FROM_NEIGHBOUR = Duration.ofMinutes(10);

    /**
     * The reports with blank brigades filled in from the same bus's previous (else next) report on the same line,
     * when close in time: the feed sometimes drops the brigade for a moment, typically at a terminus.
     */
    static List<Report> withBrigades(List<Report> reports) {
        List<Report> filled = new ArrayList<>(reports);
        for (int i = 0; i < filled.size(); i++) {
            Report r = filled.get(i);
            if (r.brigade() != null && !r.brigade().isBlank()) {
                continue;
            }
            String brigade = neighbourBrigade(filled, i, -1);
            if (brigade == null) {
                brigade = neighbourBrigade(filled, i, 1);
            }
            filled.set(i, new Report(r.vehicleId(), r.vehicle(), r.time(), r.line(), brigade, r.headsign(),
                    r.delay(), r.nextSeq(), r.lat(), r.lon()));
        }
        return filled;
    }

    private static String neighbourBrigade(List<Report> reports, int i, int step) {
        Report r = reports.get(i);
        for (int j = i + step; j >= 0 && j < reports.size(); j += step) {
            Report n = reports.get(j);
            if (n.vehicleId() != r.vehicleId() || !n.line().equals(r.line())
                    || Duration.between(n.time(), r.time()).abs().compareTo(BRIGADE_FROM_NEIGHBOUR) > 0) {
                return null;
            }
            if (n.brigade() != null && !n.brigade().isBlank()) {
                return n.brigade();
            }
        }
        return null;
    }

    /** A different headsign counts as being this much further off in time. */
    private static final long HEADSIGN_PENALTY_SECONDS = 120;

    /**
     * The trip of the brigade a report belongs to: on the same line, the one scheduled to leave the report's next stop
     * closest to the report's scheduled time (within {@link #MATCH_TOLERANCE}); a different headsign counts as two
     * minutes further. The feed's headsigns don't always read as the timetable's, so the stop and time decide: a bus
     * waiting at a terminus belongs to the trip it will start, not the one it finished.
     */
    static ScheduledTrip match(Report r, List<ScheduledTrip> brigadeTrips) {
        if (brigadeTrips == null || r.nextSeq() == null) {
            return null;
        }
        Instant scheduled = r.scheduled();
        ScheduledTrip best = null;
        long bestScore = Long.MAX_VALUE;
        for (ScheduledTrip t : brigadeTrips) {
            if (!t.line().equals(r.line())) {
                continue;
            }
            long score = Math.abs(Duration.between(t.scheduledAt(r.nextSeq()), scheduled).toSeconds())
                    + (Objects.equals(t.headsign(), r.headsign()) ? 0 : HEADSIGN_PENALTY_SECONDS);
            if (score < bestScore) {
                best = t;
                bestScore = score;
            }
        }
        return bestScore <= MATCH_TOLERANCE.toSeconds() ? best : null;
    }

    private record Context(Map<String, List<ScheduledTrip>> byBrigade, Map<ScheduledTrip, List<Report>> matched,
                           Map<Long, List<Report>> byVehicle, Recording recording, Map<String, Shape> shapes,
                           Instant now, Set<String> linesReported, Set<String> linesMatched) {

        Outcome judge(ScheduledTrip trip) {
            if (trip.departure().isAfter(now)) {
                return outcome(trip, Kind.PENDING);
            }
            Instant until = min(now, trip.arrival().plus(DUE));
            if (!recording.covers(trip.departure().minus(Duration.ofMinutes(2)), until)) {
                return outcome(trip, Kind.NOT_RECORDED);
            }
            if (linesReported.contains(trip.line()) && !linesMatched.contains(trip.line())) {
                // Buses reported the line, but none could be matched to its trips: a naming problem, not data.
                return outcome(trip, Kind.NOT_RECORDED);
            }
            boolean settled = !now.isBefore(trip.arrival().plus(SETTLE));
            List<Report> reports = matched.getOrDefault(trip, List.of());
            return reports.isEmpty() ? notRun(trip, settled) : run(trip, reports);
        }

        private Outcome notRun(ScheduledTrip trip, boolean settled) {
            if (!settled && now.isBefore(trip.departure().plus(DUE))) {
                return outcome(trip, Kind.PENDING);
            }
            String sofar = settled ? "" : "So far: ";
            List<ScheduledTrip> run = byBrigade.get(trip.brigade()).stream().filter(matched::containsKey).toList();
            if (!settled && runningLate(trip, run)) {
                return outcome(trip, Kind.PENDING);
            }
            if (run.isEmpty()) {
                String note = sofar + (linesReported.contains(trip.line())
                        ? "No bus ran brigade %s all day while recording.".formatted(trip.brigade())
                        : "No bus reported line %s all day while recording.".formatted(trip.line()));
                return abnormal(trip, Kind.CANCELLED, note, List.of(), null);
            }
            ScheduledTrip first = run.get(0);
            ScheduledTrip last = run.get(run.size() - 1);
            String where = trip.departure().isBefore(first.departure())
                    ? "Before the brigade's first run that day (it started late at %s)."
                    .formatted(ServiceDay.localTime(first.departure()))
                    : trip.departure().isAfter(last.departure())
                    ? "After the brigade's last run that day (it finished early, at %s)."
                    .formatted(ServiceDay.localTime(last.arrival()))
                    : "Between the brigade's runs.";
            return abnormal(trip, Kind.MISSED, sofar + where + " " + whereabouts(trip, run), List.of(), null);
        }

        /** Whether the brigade's bus is still on an earlier trip of the brigade, so this one can't have started. */
        private boolean runningLate(ScheduledTrip trip, List<ScheduledTrip> run) {
            ScheduledTrip earlier = run.stream().filter(t -> t.departure().isBefore(trip.departure()))
                    .reduce((a, b) -> b).orElse(null);
            if (earlier == null) {
                return false;
            }
            List<Report> reports = matched.get(earlier);
            Report last = reports.get(reports.size() - 1);
            Report latest = before(byVehicle.get(last.vehicleId()), now.plusSeconds(1));
            return latest != null && Objects.equals(latest.brigade(), trip.brigade())
                    && Duration.between(latest.time(), now).compareTo(Duration.ofMinutes(5)) <= 0;
        }

        /** Where the brigade's bus (from its nearest run) was while this trip should have run. */
        private String whereabouts(ScheduledTrip trip, List<ScheduledTrip> run) {
            ScheduledTrip near = run.stream().filter(t -> t.departure().isBefore(trip.departure()))
                    .reduce((a, b) -> b).orElse(run.get(0));
            List<Report> nearReports = matched.get(near);
            Report anyReport = nearReports.get(nearReports.size() - 1);
            String bus = "Bus " + anyReport.vehicle();
            List<Report> during = between(byVehicle.get(anyReport.vehicleId()), trip.departure(), trip.arrival());
            if (during.isEmpty()) {
                return bus + " wasn't reporting then.";
            }
            Map<String, Long> counts = during.stream().collect(Collectors.groupingBy(
                    r -> r.line() + "|" + Objects.toString(r.brigade(), ""), Collectors.counting()));
            String top = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();
            String line = top.substring(0, top.indexOf('|'));
            String brigade = top.substring(top.indexOf('|') + 1);
            if (brigade.equals(trip.brigade())) {
                Report r = during.get(0);
                Integer delay = r.usableDelay();
                return bus + " was on the brigade, reporting another of its trips"
                        + (r.headsign() == null ? "" : " (towards " + r.headsign() + ")")
                        + (delay == null || Math.abs(delay) < 60 ? "." : ", %d min %s.".formatted(
                        Math.round(Math.abs(delay) / 60.0), delay > 0 ? "late" : "early"));
            }
            return bus + " was on line " + line + (brigade.isEmpty() ? "" : " (brigade " + brigade + ")") + ".";
        }

        private Outcome run(ScheduledTrip trip, List<Report> reports) {
            List<Report> sorted = reports.stream().sorted(Comparator.comparing(Report::time)).toList();
            int fromIndex = sorted.stream().mapToInt(r -> trip.index(r.nextSeq())).min().orElse(0);
            int toIndex = sorted.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
            boolean started = fromIndex < START_STOPS;
            boolean finished = toIndex >= trip.size() - END_STOPS;
            Detour detour = TripAnomalies.detour(trip, sorted, shapes.get(trip.shapeId()));
            String detourNote = detour == null ? "" : " Off the route for %d min, up to %d m away, after %s."
                    .formatted(detour.minutes(), detour.meters(), detour.from());

            // Judged as soon as what the bus did is clear; until then pending (a detour shows already).
            Outcome pending = detour == null ? outcome(trip, Kind.PENDING)
                    : abnormal(trip, Kind.DETOUR, ("Under way." + detourNote).strip(), sorted, detour);
            if (started && finished) {
                return detour == null ? outcome(trip, Kind.COMPLETED)
                        : abnormal(trip, Kind.DETOUR, ("Run in full." + detourNote).strip(), sorted, detour);
            }
            // Only a bus reporting all along shows what it did; a gap in its reports hides it (tracking, not service).
            List<String> notes = new ArrayList<>();
            Kind kind = Kind.NO_SIGNAL;
            if (!started) {
                Report first = sorted.get(0);
                Report before = before(byVehicle.get(first.vehicleId()), first.time());
                String where = "heading to %s (stop %d of %d) at %s".formatted(trip.stops()[fromIndex],
                        fromIndex + 1, trip.size(), ServiceDay.localTime(first.time()));
                if (continuous(before, first)) {
                    kind = Kind.SHORT_TURNED;
                    notes.add("Started midway: joined %s, coming from %s.".formatted(where, which(trip, before,
                            "previous")));
                } else {
                    notes.add("Bus %s started reporting midway, %s.".formatted(first.vehicle(), where));
                }
            }
            if (!finished) {
                // Its last report at the furthest stop it reached (later ones may be waiting there to turn).
                Report last = sorted.stream().filter(rep -> trip.index(rep.nextSeq()) == toIndex)
                        .reduce((a, b) -> b).orElseThrow();
                String leaving = "heading to %s (stop %d of %d) at %s".formatted(trip.stops()[toIndex], toIndex + 1,
                        trip.size(), ServiceDay.localTime(last.time()));
                Report next = after(byVehicle.get(last.vehicleId()), last.time());
                if (continuous(last, next)) {
                    kind = Kind.SHORT_TURNED;
                    notes.add("Left the trip %s and went on to %s.".formatted(leaving, which(trip, next, "next")));
                } else if (next != null && Objects.equals(next.brigade(), trip.brigade())) {
                    notes.add("Bus %s stopped reporting %s; back at %s on %s, so it probably finished the trip."
                            .formatted(last.vehicle(), leaving, ServiceDay.localTime(next.time()),
                                    which(trip, next, "next")));
                } else {
                    Instant lostBy = last.time().plus(LOST_AFTER);
                    if (next == null && lostBy.isAfter(now)) {
                        return pending; // still on the trip, or not gone long enough to tell
                    }
                    if (next == null && !recording.covers(last.time(), lostBy)) {
                        // Recording stopped too soon to tell whether the bus came back.
                        return outcome(trip, Kind.NOT_RECORDED);
                    }
                    kind = Kind.LOST;
                    notes.add("Bus %s stopped reporting %s".formatted(last.vehicle(), leaving)
                            + (next == null ? " and wasn't seen again that day."
                            : "; seen again at %s on %s.".formatted(ServiceDay.localTime(next.time()),
                            which(trip, next, "next"))));
                }
            }
            return abnormal(trip, kind, (String.join(" ", notes) + detourNote).strip(), sorted, detour);
        }

        /** "the brigade's next trip towards X", or "line 10 (brigade 010-02)": what a report was on. */
        private static String which(ScheduledTrip trip, Report r, String nextOrPrevious) {
            if (Objects.equals(r.brigade(), trip.brigade())) {
                return "the brigade's " + nextOrPrevious + " trip"
                        + (r.headsign() == null ? "" : " towards " + r.headsign());
            }
            return "line " + r.line() + (r.brigade() == null ? "" : " (brigade " + r.brigade() + ")");
        }

        private Outcome abnormal(ScheduledTrip trip, Kind kind, String note, List<Report> reports, Detour detour) {
            List<String> vehicles = reports.stream().map(Report::vehicle)
                    .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
            Integer fromStop = null;
            Integer toStop = null;
            String coveredFrom = null;
            String coveredTo = null;
            if (!reports.isEmpty()) {
                int from = reports.stream().mapToInt(r -> trip.index(r.nextSeq())).min().orElse(0);
                int to = reports.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
                fromStop = from + 1;
                toStop = to + 1;
                coveredFrom = trip.stops()[from];
                coveredTo = trip.stops()[to];
            }
            List<Integer> delays = reports.stream().map(Report::usableDelay).filter(Objects::nonNull).toList();
            Function<Integer, Integer> at = i -> delays.isEmpty() ? null : delays.get(i);
            TripAnomalyDto.Trip dto = new TripAnomalyDto.Trip(kind.key, note, trip.tripId(), trip.line(),
                    trip.brigade(), trip.headsign(), trip.stops()[0], trip.stops()[trip.size() - 1],
                    trip.departure(), trip.arrival(), trip.size(), vehicles, fromStop, toStop, coveredFrom, coveredTo,
                    reports.isEmpty() ? null : reports.get(0).time(),
                    reports.isEmpty() ? null : reports.get(reports.size() - 1).time(),
                    at.apply(0), delays.stream().max(Integer::compare).orElse(null),
                    at.apply(delays.size() - 1),
                    detour == null ? null : detour.meters(), detour == null ? null : detour.minutes(),
                    detour == null ? null : detour.from(), detour == null ? null : detour.to());
            return new Outcome(trip.line(), trip.departure(), trip.arrival(), kind, dto);
        }

        private static Instant min(Instant a, Instant b) {
            return a.isBefore(b) ? a : b;
        }
    }

    /** A vehicle's reports in {@code [from, to]} (its reports are in time order). */
    static List<Report> between(List<Report> reports, Instant from, Instant to) {
        if (reports == null) {
            return List.of();
        }
        return reports.stream().filter(r -> !r.time().isBefore(from) && !r.time().isAfter(to)).toList();
    }

    /** Two reports this close in time show a bus reporting all along. */
    static final Duration CONTINUOUS = Duration.ofSeconds(90);

    /** Whether the vehicle reported all along from {@code a} to {@code b} (both present, close in time). */
    static boolean continuous(Report a, Report b) {
        return a != null && b != null && Duration.between(a.time(), b.time()).compareTo(CONTINUOUS) <= 0;
    }

    /** A vehicle's last report before {@code time}, or null. */
    static Report before(List<Report> reports, Instant time) {
        if (reports == null) {
            return null;
        }
        Report found = null;
        for (Report r : reports) {
            if (!r.time().isBefore(time)) {
                break;
            }
            found = r;
        }
        return found;
    }

    /** A vehicle's first report after {@code time}, or null. */
    static Report after(List<Report> reports, Instant time) {
        if (reports == null) {
            return null;
        }
        return reports.stream().filter(r -> r.time().isAfter(time)).findFirst().orElse(null);
    }

    // ---- The window -----------------------------------------------------------------------------------------

    /** Outcomes counted. */
    static TripAnomalyDto.Summary summary(List<Outcome> outcomes) {
        int[] n = counts(outcomes);
        return new TripAnomalyDto.Summary(outcomes.size(), outcomes.size() - n[Kind.NOT_RECORDED.ordinal()]
                - n[Kind.PENDING.ordinal()], n[Kind.COMPLETED.ordinal()], n[Kind.DETOUR.ordinal()],
                n[Kind.NO_SIGNAL.ordinal()], n[Kind.SHORT_TURNED.ordinal()], n[Kind.LOST.ordinal()], n[Kind.MISSED.ordinal()],
                n[Kind.CANCELLED.ordinal()], n[Kind.NOT_RECORDED.ordinal()], n[Kind.PENDING.ordinal()]);
    }

    /** Outcomes per line, the lines with the most abnormal trips first. */
    static List<TripAnomalyDto.LineCounts> lines(List<Outcome> outcomes) {
        Map<String, List<Outcome>> byLine = outcomes.stream().filter(o -> o.kind().judged())
                .collect(Collectors.groupingBy(Outcome::line));
        return byLine.entrySet().stream().map(e -> {
                    int[] n = counts(e.getValue());
                    return new TripAnomalyDto.LineCounts(e.getKey(), e.getValue().size(), n[Kind.COMPLETED.ordinal()],
                            n[Kind.DETOUR.ordinal()], n[Kind.NO_SIGNAL.ordinal()], n[Kind.SHORT_TURNED.ordinal()],
                            n[Kind.LOST.ordinal()],
                            n[Kind.MISSED.ordinal()], n[Kind.CANCELLED.ordinal()]);
                })
                .sorted(Comparator.comparingInt((TripAnomalyDto.LineCounts c) -> c.completed() - c.judged())
                        .thenComparing(TripAnomalyDto.LineCounts::line, TimetableMath.LINE_ORDER))
                .toList();
    }

    private static int[] counts(List<Outcome> outcomes) {
        int[] n = new int[Kind.values().length];
        outcomes.forEach(o -> n[o.kind().ordinal()]++);
        return n;
    }
}
