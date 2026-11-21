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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.transitanalytics.service.TripHandovers.*;
import static com.transitanalytics.service.TripMatching.*;
import static com.transitanalytics.service.TripShapes.*;
import static com.transitanalytics.service.TripSummaries.*;

/**
 * Judges a service day's scheduled trips against the recorded positions. Each report is matched to a trip of its
 * brigade by scheduled time (the report's time minus its delay) and line, preferring the same headsign; a trip's
 * reports tell which of its stops the bus was heading to, and how far it was from the trip's route shape. The parts
 * are in {@link TripMatching}, {@link TripShapes}, {@link TripHandovers} and {@link TripSummaries}; this holds the
 * thresholds, the records they share and the judging itself.
 */
final class TripAnomalies {

    /** A report matches a trip scheduled to leave its next stop at most this far from the report's scheduled time. */
    static final Duration MATCH_TOLERANCE = Duration.ofMinutes(20);
    /** A run counts as started if first seen heading to one of the trip's first stops (reports come every 10 s). */
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
    /**
     * A bus counts as having run part of a trip with this many reports on it (about a minute); fewer can be a
     * mislabelled brigade for a moment.
     */
    static final int CHANGE_REPORTS = 3;
    /** Two buses' reports on a trip may overlap this much at a change (both reporting at the handover). */
    static final Duration CHANGE_OVERLAP = Duration.ofMinutes(2);
    /**
     * A replaced bus counts as used again once it reports a brigade this far from where it left the trip; a broken
     * bus standing still keeps reporting, and the feed sometimes flips it to another trip of its brigade.
     */
    static final double MOVED_METERS = 500;
    /** …and keeps reporting a brigade this long after (a bus driven back to the depot may report it once more)… */
    static final Duration USED_FOR = Duration.ofMinutes(10);
    /**
     * …with a delay at most this big: a bus leaving service still logged in to its brigade reports it, but running
     * against the route with its delay growing by the minute.
     */
    static final int IN_SERVICE_DELAY_SECONDS = 600;
    /** A replaced bus last seen standing this recently is still there, rather than gone silent. */
    static final Duration STILL_THERE = Duration.ofMinutes(GAP_MINUTES);
    /** A replacement's reports this close together lead up to its takeover: its way to the trip. */
    static final Duration APPROACH_GAP = Duration.ofMinutes(10);
    /** A replacement whose last brigade before ended at most this long before it took over came straight from it. */
    static final Duration STRAIGHT_FROM = Duration.ofMinutes(30);
    /** Two reports of a detour are consecutive when at most this far apart. */
    static final Duration DETOUR_REPORT_GAP = Duration.ofSeconds(90);

    private TripAnomalies() {
    }

    enum Kind {
        COMPLETED("completed"), DETOUR("detour"), VEHICLE_CHANGED("vehicle-changed"), NO_SIGNAL("no-signal"), SHORT_TURNED("short-turned"),
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
                         Instant arrival, int[] seqs, String[] stops, int[] offsets, String shapeId,
                         double[] lastStop) {

        /** A trip whose last stop's position ([latitude, longitude]) is unknown (layovers there aren't told apart). */
        ScheduledTrip(String tripId, String line, String brigade, String headsign, Instant departure,
                      Instant arrival, int[] seqs, String[] stops, int[] offsets, String shapeId) {
            this(tripId, line, brigade, headsign, departure, arrival, seqs, stops, offsets, shapeId, null);
        }

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

    /**
     * A judged trip: its line, scheduled run and outcome, and the details when it is abnormal.
     *
     * @param vehicleIds the buses seen on it, in order (ids; the trip lists their numbers)
     */
    record Outcome(String line, Instant departure, Instant arrival, Kind kind, TripAnomalyDto.Trip trip,
                   List<Long> vehicleIds) {
    }

    private static Outcome outcome(ScheduledTrip trip, Kind kind) {
        return new Outcome(trip.line(), trip.departure(), trip.arrival(), kind, null, List.of());
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
        Map<String, List<ScheduledTrip>> byBrigade = byBrigade(trips);
        List<Report> filled = withBrigades(reports);
        Map<ScheduledTrip, List<Report>> matched = matchReports(byBrigade, filled);
        Map<Long, List<Report>> byVehicle = new HashMap<>();
        Set<String> linesReported = new HashSet<>();
        for (Report r : filled) {
            byVehicle.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>()).add(r);
            linesReported.add(r.line());
        }
        Set<String> linesMatched = matched.keySet().stream().map(ScheduledTrip::line).collect(Collectors.toSet());

        Context context = new Context(byBrigade, matched, byVehicle, recording, shapes, now, linesReported,
                linesMatched);
        return trips.stream().map(context::judge).toList();
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
                return abnormal(trip, Kind.CANCELLED, note, List.of(), null, null);
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
            Outcome missed = abnormal(trip, Kind.MISSED, sofar + where + " " + whereabouts(trip, run), List.of(), null,
                    null);
            // Not seen on the trip, but the brigade's bus that day (of its nearest run) is the one that missed it.
            return new Outcome(missed.line(), missed.departure(), missed.arrival(), missed.kind(), missed.trip(),
                    List.of(nearestBus(trip, run).vehicleId()));
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

        /** The last report of the brigade's bus on its run nearest before this trip (else its first run). */
        private Report nearestBus(ScheduledTrip trip, List<ScheduledTrip> run) {
            ScheduledTrip near = run.stream().filter(t -> t.departure().isBefore(trip.departure()))
                    .reduce((a, b) -> b).orElse(run.get(0));
            List<Report> nearReports = matched.get(near);
            return nearReports.get(nearReports.size() - 1);
        }

        /** Where the brigade's bus (from its nearest run) was while this trip should have run. */
        private String whereabouts(ScheduledTrip trip, List<ScheduledTrip> run) {
            Report anyReport = nearestBus(trip, run);
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
            Detour detour = TripShapes.detour(trip, sorted, shapes.get(trip.shapeId()));
            String detourNote = detour == null ? "" : " Off the route for %d min, up to %d m away, after %s."
                    .formatted(detour.minutes(), detour.meters(), detour.from());
            Change change = vehicleChange(trip, sorted);
            Handover handover = change == null ? null : handover(trip, change, sorted);
            String changeNote = handover == null ? "" : " " + handover.note();

            // Judged as soon as what the bus did is clear; until then pending (a change or detour shows already).
            Outcome pending = change != null
                    ? abnormal(trip, Kind.VEHICLE_CHANGED, ("Under way." + changeNote + detourNote).strip(), sorted,
                    detour, handover)
                    : detour == null ? outcome(trip, Kind.PENDING)
                    : abnormal(trip, Kind.DETOUR, ("Under way." + detourNote).strip(), sorted, detour, null);
            if (started && finished) {
                return change != null
                        ? abnormal(trip, Kind.VEHICLE_CHANGED, ("Run in full." + changeNote + detourNote).strip(),
                        sorted, detour, handover)
                        : detour == null ? outcome(trip, Kind.COMPLETED)
                        : abnormal(trip, Kind.DETOUR, ("Run in full." + detourNote).strip(), sorted, detour, null);
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
            return abnormal(trip, kind, (String.join(" ", notes) + changeNote + detourNote).strip(), sorted, detour,
                    handover);
        }

        /**
         * A change of bus in words, with where the replacement came from and what became of the replaced bus: used
         * again only once it reports a brigade {@link #MOVED_METERS} away from where it left the trip.
         */
        /** A delay as "+2:05" or "-0:40". */
        private static String signed(int seconds) {
            return (seconds < 0 ? "-" : "+") + "%d:%02d".formatted(Math.abs(seconds) / 60, Math.abs(seconds) % 60);
        }

        private Handover handover(ScheduledTrip trip, Change c, List<Report> sorted) {
            Report a = c.fromLast();
            Report b = c.toFirst();
            int stoppedAt = c.stoppedIndex(trip);
            StringBuilder note = new StringBuilder("Bus %s stopped heading to %s (stop %d of %d) at %s; "
                    .formatted(a.vehicle(), trip.stops()[stoppedAt], stoppedAt + 1, trip.size(),
                            ServiceDay.localTime(c.stopped().time()))
                    + "%d min later bus %s took over %s, heading to %s (stop %d)."
                    .formatted(c.standMinutes(), b.vehicle(), joined(trip, c), trip.stops()[c.toIndex()],
                            c.toIndex() + 1)
                    + (c.delayBefore() == null || c.delayAfter() == null ? ""
                    : " Delay %s before, %s after.".formatted(signed(c.delayBefore()), signed(c.delayAfter()))));

            // Where the replacement came from: its last report on another brigade before.
            List<Report> bReports = byVehicle.getOrDefault(b.vehicleId(), List.of());
            Report origin = null;
            for (Report r : bReports) {
                if (!r.time().isBefore(b.time())) {
                    break;
                }
                if (hasBrigade(r) && !(r.brigade().equals(trip.brigade()) && r.line().equals(trip.line()))) {
                    origin = r;
                }
            }
            String originWhere = origin == null ? null : origin.line().equals(trip.line())
                    ? "brigade %s of the same line".formatted(origin.brigade())
                    : "line %s (brigade %s)".formatted(origin.line(), origin.brigade());
            boolean straight = origin != null && !origin.time().plus(STRAIGHT_FROM).isBefore(b.time());
            if (origin == null) {
                note.append(" Bus %s hadn't run any other brigade that day (from the depot).".formatted(b.vehicle()));
            } else {
                note.append((straight ? " Bus %s came straight from %s, until %s."
                        : " Bus %s had run %s earlier that day, until %s.").formatted(b.vehicle(), originWhere,
                        ServiceDay.localTime(origin.time())));
            }

            // What became of the replaced bus.
            List<Report> later = byVehicle.getOrDefault(a.vehicleId(), List.of()).stream()
                    .filter(r -> r.time().isAfter(a.time())).toList();
            Report used = later.stream().filter(r -> inService(r) && metres(a, r) > MOVED_METERS
                            && later.stream().anyMatch(q -> inService(q)
                            && !q.time().isBefore(r.time().plus(USED_FOR))))
                    .findFirst().orElse(null);
            Fate fate;
            Stand stand = null;
            if (used == null) {
                fate = Fate.BROKEN_DOWN;
                stand = stand(c.stopped(), a, later);
                note.append(" ").append(standNote(a.vehicle(), stand));
            } else if (used.brigade().equals(trip.brigade()) && used.line().equals(trip.line())) {
                fate = Fate.RETURNED;
                note.append(" Bus %s was back on the brigade at %s.".formatted(a.vehicle(),
                        ServiceDay.localTime(used.time())));
            } else {
                fate = Fate.REASSIGNED;
                note.append(" Bus %s was used again on %s from %s.".formatted(a.vehicle(), where(trip, used),
                        ServiceDay.localTime(used.time())));
            }

            // Step by step, and the buses' time lines.
            List<TripAnomalyDto.Step> story = new ArrayList<>();
            List<TripAnomalyDto.Span> spans = new ArrayList<>();
            Report first = c.fromFirst();
            int firstAt = trip.index(first.nextSeq());
            story.add(new TripAnomalyDto.Step(first.time(), "Bus %s runs line %s towards %s, heading to %s (stop %d of %d)%s."
                    .formatted(a.vehicle(), trip.line(), Objects.toString(trip.headsign(), trip.stops()[trip.size() - 1]),
                            trip.stops()[firstAt], firstAt + 1, trip.size(), delayPart(first.usableDelay()))));
            Instant leftAt = stand == null ? a.time() : stand.until();
            int peak = Stream.concat(Stream.of(a), later.stream())
                    .filter(r -> !r.time().isBefore(c.stopped().time()) && !r.time().isAfter(leftAt)
                            && r.delay() != null)
                    .mapToInt(Report::delay).max().orElse(Integer.MIN_VALUE);
            boolean growing = stand != null && c.delayBefore() != null && peak >= c.delayBefore() + 120;
            story.add(new TripAnomalyDto.Step(c.stopped().time(), "Bus %s stops heading to %s (stop %d of %d%s) %s."
                    .formatted(a.vehicle(), trip.stops()[stoppedAt], stoppedAt + 1, trip.size(),
                            c.delayBefore() == null ? "" : "; delay " + signed(c.delayBefore()),
                            stand == null ? "and leaves the trip"
                                    : "and doesn't move on" + (growing
                                    ? "; the feed's delay keeps growing, to %s by %s".formatted(signed(peak),
                                    ServiceDay.localTime(leftAt)) : ""))));
            spans.add(new TripAnomalyDto.Span(a.vehicle(), "running", first.time(), c.stopped().time()));
            spans.add(new TripAnomalyDto.Span(a.vehicle(), "standing", c.stopped().time(), leftAt));
            if (stand != null) {
                switch (stand.then()) {
                    case VANISHED -> story.add(new TripAnomalyDto.Step(stand.until(),
                            "Last report of bus %s, from the same spot: not seen again that day (switched off, or "
                                    .formatted(a.vehicle()) + "towed with its tracker off)."));
                    case STILL_THERE -> story.add(new TripAnomalyDto.Step(stand.until(),
                            "Bus %s is still standing there at its latest report.".formatted(a.vehicle())));
                    case DROVE_OFF -> {
                        story.add(new TripAnomalyDto.Step(stand.moved(), "Bus %s moves off without running a brigade."
                                .formatted(a.vehicle())));
                        story.add(new TripAnomalyDto.Step(stand.lastSeen().time(),
                                "Last report of bus %s, %s from where it stopped; not used again that day."
                                        .formatted(a.vehicle(), distance(stand.metres()))));
                        spans.add(new TripAnomalyDto.Span(a.vehicle(), "moving", stand.moved(),
                                stand.lastSeen().time()));
                    }
                }
            } else {
                story.add(new TripAnomalyDto.Step(used.time(), fate == Fate.RETURNED
                        ? "Bus %s is back on the brigade.".formatted(a.vehicle())
                        : "Bus %s is used again on %s.".formatted(a.vehicle(), where(trip, used))));
                spans.add(new TripAnomalyDto.Span(a.vehicle(), "service", used.time(), used.time()));
            }

            // The replacement: where from, its way to the trip, and on.
            Report approach = approachStart(bReports, b, origin);
            if (origin != null) {
                story.add(new TripAnomalyDto.Step(origin.time(), (straight ? "Bus %s leaves %s."
                        : "Bus %s finishes %s.").formatted(b.vehicle(), originWhere)));
            }
            if (approach != null) {
                story.add(new TripAnomalyDto.Step(approach.time(), (origin == null
                        ? "Bus %s sets off without a brigade (from the depot), %s from where bus %s stopped."
                        : "Bus %s heads for the trip without a brigade, %s from where bus %s stopped.")
                        .formatted(b.vehicle(), distance((int) Math.round(metres(approach, c.stopped()))),
                                a.vehicle())));
                spans.add(new TripAnomalyDto.Span(b.vehicle(), "approach", approach.time(), b.time()));
            }
            story.add(new TripAnomalyDto.Step(b.time(), "Bus %s takes over %s, heading to %s (stop %d of %d), %s from where bus %s stopped%s.%s"
                    .formatted(b.vehicle(), joined(trip, c), trip.stops()[c.toIndex()], c.toIndex() + 1, trip.size(),
                            distance((int) Math.round(metres(b, c.stopped()))), a.vehicle(),
                            delayPart(c.delayAfter()),
                            c.standMinutes() >= 1 ? " The trip had no bus for %d min.".formatted(c.standMinutes())
                                    : "")));
            Instant serviceUntil = sorted.stream().filter(r -> r.vehicleId() == b.vehicleId())
                    .map(Report::time).max(Comparator.naturalOrder()).orElse(b.time());
            ScheduledTrip next = byBrigade.getOrDefault(trip.brigade(), List.of()).stream()
                    .filter(t -> t.departure().isAfter(trip.departure())).findFirst().orElse(null);
            List<Report> onNext = next == null ? List.of() : matched.getOrDefault(next, List.of()).stream()
                    .filter(r -> r.vehicleId() == b.vehicleId()).toList();
            if (!onNext.isEmpty()) {
                story.add(new TripAnomalyDto.Step(onNext.get(0).time(),
                        "Bus %s runs the brigade's next trip, towards %s (scheduled %s).".formatted(b.vehicle(),
                                Objects.toString(next.headsign(), next.stops()[next.size() - 1]),
                                ServiceDay.localTime(next.departure()))));
                serviceUntil = onNext.get(onNext.size() - 1).time();
            }
            spans.add(new TripAnomalyDto.Span(b.vehicle(), "service", b.time(), serviceUntil));
            story.sort(Comparator.comparing(TripAnomalyDto.Step::at));
            return new Handover(c, fate, stand, origin == null ? null : origin.line(),
                    origin == null ? null : origin.brigade(), note.toString(), List.copyOf(story),
                    spans.stream().filter(sp -> !sp.to().isBefore(sp.from())).toList());
        }

        /** "at the same place", "3 stops further on" or "2 stops back": where the second bus joined. */
        private static String joined(ScheduledTrip trip, Change c) {
            int skipped = c.skippedStops(trip);
            int back = c.stopsBack(trip);
            return c.samePlace(trip) ? "at the same place"
                    : back > 0 ? (back == 1 ? "1 stop back" : back + " stops back")
                    : skipped == 1 ? "1 stop further on" : skipped + " stops further on";
        }

        /** "brigade 010-02 of the same line" or "line 12 (brigade 012-01)". */
        private static String where(ScheduledTrip trip, Report r) {
            return r.line().equals(trip.line()) ? "brigade " + r.brigade() + " of the same line"
                    : "line " + r.line() + " (brigade " + r.brigade() + ")";
        }

        /** ", delay +2:05", or nothing when unknown. */
        private static String delayPart(Integer delay) {
            return delay == null ? "" : ", delay " + signed(delay);
        }

        /**
         * Where the replacement's way to the trip began: the first of its reports leading up to the takeover with
         * no gap longer than {@link #APPROACH_GAP}, after its last other brigade; null when it was first seen on the
         * trip.
         */
        private static Report approachStart(List<Report> reports, Report takeover, Report origin) {
            Report start = null;
            Instant after = takeover.time();
            for (int i = reports.size() - 1; i >= 0; i--) {
                Report r = reports.get(i);
                if (!r.time().isBefore(takeover.time())) {
                    continue;
                }
                if (origin != null && !r.time().isAfter(origin.time())
                        || Duration.between(r.time(), after).compareTo(APPROACH_GAP) > 0) {
                    break;
                }
                start = r;
                after = r.time();
            }
            return start;
        }

        /**
         * How a bus that stopped at {@code spot} spent the rest of the day: standing there while its reports stay
         * within {@link #MOVED_METERS} (it did at least until {@code left}, its last report on the trip); then gone
         * silent, moved away, or still there.
         */
        private Stand stand(Report spot, Report left, List<Report> later) {
            Report standing = left;
            Report moved = null;
            for (Report r : later) {
                if (r.time().isBefore(spot.time())) {
                    continue;
                }
                if (metres(spot, r) > MOVED_METERS) {
                    moved = r;
                    break;
                }
                standing = r;
            }
            Report lastSeen = later.isEmpty() ? standing : later.get(later.size() - 1);
            if (lastSeen.time().isBefore(standing.time())) {
                lastSeen = standing;
            }
            Then then = moved != null ? Then.DROVE_OFF
                    : Duration.between(standing.time(), now).compareTo(STILL_THERE) <= 0 ? Then.STILL_THERE
                    : Then.VANISHED;
            return new Stand(spot.time(), standing.time(), then, moved == null ? standing : lastSeen,
                    (int) Math.round(metres(spot, moved == null ? standing : lastSeen)),
                    moved == null ? null : moved.time());
        }

        /** What a replaced bus that wasn't used again did, in words. */
        private String standNote(String vehicle, Stand s) {
            String from = ServiceDay.localTime(s.stopped());
            String until = ServiceDay.localTime(s.until());
            String stood = "Bus %s stood there for %d min (%s–%s)".formatted(vehicle, s.minutes(), from, until);
            return switch (s.then()) {
                case STILL_THERE -> "Bus %s has stood there since %s (%d min, last report %s) and hasn't been used "
                        .formatted(vehicle, from, s.minutes(), until) + "since: possibly broken down.";
                case VANISHED -> (s.minutes() < 1
                        ? "Bus %s stopped reporting right there".formatted(vehicle)
                        : stood + ", then stopped reporting")
                        + " and wasn't seen again that day (switched off, or towed with its tracker off): "
                        + "possibly broken down.";
                case DROVE_OFF -> (s.minutes() < 1 ? "Bus %s moved off right away".formatted(vehicle)
                        : stood + ", then moved off") + " without running a brigade; last seen at %s, %s away. "
                        .formatted(ServiceDay.localTime(s.lastSeen().time()), distance(s.metres()))
                        + "Not used again that day: possibly broken down.";
            };
        }

        /** "800 m" or "4.2 km". */
        private static String distance(int metres) {
            return metres < 1000 ? (metres / 50 * 50) + " m" : String.format(Locale.ROOT, "%.1f km", metres / 1000.0);
        }

        /** "the brigade's next trip towards X", or "line 10 (brigade 010-02)": what a report was on. */
        private static String which(ScheduledTrip trip, Report r, String nextOrPrevious) {
            if (Objects.equals(r.brigade(), trip.brigade())) {
                return "the brigade's " + nextOrPrevious + " trip"
                        + (r.headsign() == null ? "" : " towards " + r.headsign());
            }
            return "line " + r.line() + (r.brigade() == null ? "" : " (brigade " + r.brigade() + ")");
        }

        private Outcome abnormal(ScheduledTrip trip, Kind kind, String note, List<Report> reports, Detour detour,
                                 Handover handover) {
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
                    detour == null ? null : detour.from(), detour == null ? null : detour.to(),
                    detour == null ? List.of() : detour.path(), detour == null ? List.of() : detour.route(),
                    handover == null ? null : handover.fate().key, handover == null ? null : handover.fromLine(),
                    handover == null ? null : handover.fromBrigade(),
                    handover == null ? null : handover.change().standMinutes(),
                    handover == null ? null : handover.change().delayBefore(),
                    handover == null ? null : handover.change().delayAfter(),
                    handover == null ? null : trip.stops()[handover.change().stoppedIndex(trip)],
                    handover == null ? null : trip.stops()[handover.change().toIndex()],
                    handover == null ? null : handover.change().skippedStops(trip),
                    handover == null ? null : handover.change().stopped().time(),
                    stand(handover, Stand::minutes), stand(handover, st -> st.then().key),
                    stand(handover, st -> st.lastSeen().time()), stand(handover, Stand::metres),
                    handover == null ? List.of() : handover.story(), handover == null ? List.of() : handover.spans());
            return new Outcome(trip.line(), trip.departure(), trip.arrival(), kind, dto,
                    reports.stream().map(Report::vehicleId).distinct().toList());
        }

        /** A detail of the replaced bus's stand, or null when there is none (no change, or the bus was used again). */
        private static <T> T stand(Handover handover, Function<Stand, T> detail) {
            return handover == null || handover.stand() == null ? null : detail.apply(handover.stand());
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

}
