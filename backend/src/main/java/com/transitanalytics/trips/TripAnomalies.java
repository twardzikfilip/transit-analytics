package com.transitanalytics.trips;

import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import com.transitanalytics.dto.TripAnomalyDto.Phrase;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.trips.TripHandovers.Change;
import com.transitanalytics.trips.TripHandovers.Fate;
import com.transitanalytics.trips.TripHandovers.Handover;
import com.transitanalytics.trips.TripHandovers.Stand;
import com.transitanalytics.trips.TripHandovers.Then;
import com.transitanalytics.trips.TripShapes.Detour;
import com.transitanalytics.trips.TripShapes.Shape;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.transitanalytics.trips.TripHandovers.hasBrigade;
import static com.transitanalytics.trips.TripHandovers.inService;
import static com.transitanalytics.trips.TripHandovers.metres;
import static com.transitanalytics.trips.TripHandovers.vehicleChange;
import static com.transitanalytics.trips.TripMatching.byBrigade;
import static com.transitanalytics.trips.TripMatching.matchReports;
import static com.transitanalytics.trips.TripMatching.withBrigades;

/**
 * Judges a service day's scheduled trips against the recorded positions. Each report is matched to a trip of its
 * brigade by scheduled time (the report's time minus its delay) and route, preferring the same headsign; a trip's
 * reports tell which of its stops the bus was heading to, and how far it was from the trip's route shape. The parts
 * are in {@link TripMatching}, {@link TripShapes}, {@link TripHandovers} and {@link TripSummaries}; this holds the
 * thresholds, the records they share and the judging itself.
 */
public final class TripAnomalies {

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
     * A replaced bus counts as used again once it reports a brigade this far from where it left the trip; a replaced
     * bus standing still may keep reporting, and the feed sometimes flips it to another trip of its brigade.
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
    /**
     * A bus that left a trip within this many stops of the end, reporting all along, finished it (the feed often moves
     * a bus on to its next trip two or three stops early, or it goes off to the depot from there), unless it really
     * turned short: onto its brigade's next trip, with its delay at least {@link #SHORT_TURN_GAIN_SECONDS} smaller
     * there, and not seen at the last stop afterwards. Leaving earlier is a short turn, unless it drove on to the end.
     */
    static final int SHORT_TURN_STOPS = 3;
    /** …at least this much smaller delay on the next trip (turning short is how a bus catches up)… */
    static final int SHORT_TURN_GAIN_SECONDS = 180;
    /** …and it came this close to the trip's last stop afterwards: it drove on to the end after all. */
    static final double REACHED_END_METERS = 300;
    /** The next trip's delay is its first one within this long of leaving the trip. */
    static final Duration NEXT_DELAY_WITHIN = Duration.ofMinutes(15);

    private TripAnomalies() {
    }

    public enum Kind {
        COMPLETED("completed"), DETOUR("detour"), VEHICLE_CHANGED("vehicle-changed"), NO_SIGNAL("no-signal"), SHORT_TURNED("short-turned"),
        LOST("lost"), MISSED("missed"), CANCELLED("cancelled"), NOT_RECORDED("not-recorded"), PENDING("pending");

        public final String key;

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
     * A judged trip: its id (unique on its day), route, scheduled run and outcome, and the details when it is
     * abnormal.
     *
     * @param vehicleIds the buses seen on it, in order (ids; an abnormal trip lists their numbers); for a trip not
     *                   run, the brigade's bus that day
     */
    public record Outcome(String tripId, String route, Instant departure, Instant arrival, Kind kind,
                   TripAnomalyDto.Trip trip, List<Long> vehicleIds) {
    }

    /**
     * A trip's key among a day's outcomes: its id with its route, as feeds' trip ids are only unique within the feed.
     */
    public static String key(ScheduledTrip trip) {
        return trip.route() + "|" + trip.tripId();
    }

    /** An outcome's key, as {@link #key(ScheduledTrip)}. */
    public static String key(Outcome outcome) {
        return outcome.route() + "|" + outcome.tripId();
    }

    /** The buses of some reports, in order of first report. */
    private static List<Long> vehicleIds(List<Report> reports) {
        return reports.stream().map(Report::vehicleId).distinct().toList();
    }

    private static Outcome outcome(ScheduledTrip trip, Kind kind) {
        return new Outcome(trip.tripId(), trip.route(), trip.departure(), trip.arrival(), kind, null, List.of());
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
     * @param reports the day's reports on the timetable's routes, ordered by vehicle and time
     * @param shapes  route shapes by id
     * @param now     trips not over by then are pending (or already abnormal)
     */
    static List<Outcome> judge(List<ScheduledTrip> trips, List<Report> reports, Recording recording,
                               Map<String, Shape> shapes, Instant now) {
        return judge(trips, reports, recording, shapes, now, Set.of(), Set.of()).outcomes();
    }

    /** Outcomes, and the routes with a trip some bus ran (as far as known). */
    record Judged(List<Outcome> outcomes, Set<String> routesMatched) {
    }

    /**
     * As {@link #judge(List, List, Recording, Map, Instant)}, for some brigades' trips of the day, with the reports of
     * the buses that ran them.
     *
     * @param otherLinesReported routes reported that day by any bus
     * @param otherLinesMatched  routes with a trip some bus ran, known from the day's other brigades
     */
    static Judged judge(List<ScheduledTrip> trips, List<Report> reports, Recording recording,
                        Map<String, Shape> shapes, Instant now, Set<String> otherRoutesReported,
                        Set<String> otherRoutesMatched) {
        Map<String, List<ScheduledTrip>> byBrigade = byBrigade(trips);
        List<Report> filled = withBrigades(reports);
        Map<ScheduledTrip, List<Report>> matched = matchReports(byBrigade, filled);
        Map<Long, List<Report>> byVehicle = new HashMap<>();
        Set<String> routesReported = new HashSet<>(otherRoutesReported);
        for (Report r : filled) {
            byVehicle.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>()).add(r);
            routesReported.add(r.route());
        }
        Set<String> routesMatched = new HashSet<>(otherRoutesMatched);
        matched.keySet().forEach(t -> routesMatched.add(t.route()));

        Context context = new Context(byBrigade, matched, byVehicle, recording, shapes, now, routesReported,
                routesMatched);
        return new Judged(trips.stream().map(context::judge).toList(), routesMatched);
    }

    private record Context(Map<String, List<ScheduledTrip>> byBrigade, Map<ScheduledTrip, List<Report>> matched,
                           Map<Long, List<Report>> byVehicle, Recording recording, Map<String, Shape> shapes,
                           Instant now, Set<String> routesReported, Set<String> routesMatched) {

        Outcome judge(ScheduledTrip trip) {
            if (trip.departure().isAfter(now)) {
                return outcome(trip, Kind.PENDING);
            }
            Instant until = min(now, trip.arrival().plus(DUE));
            if (!recording.covers(trip.departure().minus(Duration.ofMinutes(2)), until)) {
                return outcome(trip, Kind.NOT_RECORDED);
            }
            if (routesReported.contains(trip.route()) && !routesMatched.contains(trip.route())) {
                // Buses reported the route, but none could be matched to its trips: a naming problem, not data.
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
            List<ScheduledTrip> run = byBrigade.get(trip.brigade()).stream().filter(matched::containsKey).toList();
            if (!settled && runningLate(trip, run)) {
                return outcome(trip, Kind.PENDING);
            }
            if (run.isEmpty()) {
                return abnormal(trip, Kind.CANCELLED, List.of(AnomalyNotes.noBus()), List.of(), null, null);
            }
            Outcome missed = abnormal(trip, Kind.MISSED, List.of(whereabouts(trip, run)), List.of(), null, null);
            // Not seen on the trip, but the brigade's bus that day (of its nearest run) is the one that missed it.
            return new Outcome(missed.tripId(), missed.route(), missed.departure(), missed.arrival(), missed.kind(), missed.trip(),
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
        private Phrase whereabouts(ScheduledTrip trip, List<ScheduledTrip> run) {
            Report anyReport = nearestBus(trip, run);
            String bus = anyReport.vehicle();
            List<Report> during = between(byVehicle.get(anyReport.vehicleId()), trip.departure(), trip.arrival());
            if (during.isEmpty()) {
                return AnomalyNotes.busSilent(bus);
            }
            Map<String, Long> counts = during.stream().collect(Collectors.groupingBy(
                    r -> r.route() + "|" + Objects.toString(r.brigade(), ""), Collectors.counting()));
            String top = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();
            String route = top.substring(0, top.indexOf('|'));
            String brigade = top.substring(top.indexOf('|') + 1);
            if (brigade.equals(trip.brigade())) {
                Integer delay = during.get(0).usableDelay();
                return delay != null && delay >= 60
                        ? AnomalyNotes.busLate(bus, (int) Math.round(delay / 60.0))
                        : AnomalyNotes.busOnOtherTrip(bus);
            }
            return AnomalyNotes.busOnRoute(bus, route);
        }

        private Outcome run(ScheduledTrip trip, List<Report> reports) {
            List<Report> sorted = reports.stream().sorted(Comparator.comparing(Report::time)).toList();
            int fromIndex = sorted.stream().mapToInt(r -> trip.index(r.nextSeq())).min().orElse(0);
            int toIndex = sorted.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
            boolean started = fromIndex < START_STOPS;
            // Its last report at the furthest stop it reached (later ones may be waiting there to turn).
            Report last = sorted.stream().filter(rep -> trip.index(rep.nextSeq()) == toIndex)
                    .reduce((a, b) -> b).orElseThrow();
            Report next = after(byVehicle.get(last.vehicleId()), last.time());
            // Moved on, reporting all along, near the end or driving on to it, but not a real short turn: the feed
            // moved it on to its next trip early, or it went off to the depot from the last stops.
            boolean finished = toIndex >= trip.size() - END_STOPS
                    || continuous(last, next) && !turnedShort(trip, toIndex, last, next)
                    && (trip.size() - 1 - toIndex <= SHORT_TURN_STOPS || reachedEnd(trip, last));
            Detour detour = TripShapes.detour(trip, sorted, shapes.get(trip.shapeId()));
            List<Phrase> detourNote = detour == null ? List.of()
                    : List.of(AnomalyNotes.detour(detour.meters()));
            Change change = vehicleChange(trip, sorted);
            Handover handover = change == null ? null : handover(trip, change, sorted);
            List<Phrase> changeNote = handover == null ? List.of() : handover.note();

            // Judged as soon as what the bus did is clear; until then pending (a change or detour shows already).
            Outcome pending = change != null
                    ? abnormal(trip, Kind.VEHICLE_CHANGED, join(changeNote, detourNote), sorted, detour, handover)
                    : detour == null ? outcome(trip, Kind.PENDING)
                    : abnormal(trip, Kind.DETOUR, detourNote, sorted, detour, null);
            if (started && finished) {
                return change != null
                        ? abnormal(trip, Kind.VEHICLE_CHANGED, join(changeNote, detourNote), sorted, detour, handover)
                        : detour == null ? new Outcome(trip.tripId(), trip.route(), trip.departure(), trip.arrival(),
                        Kind.COMPLETED, null, vehicleIds(sorted))
                        : abnormal(trip, Kind.DETOUR, detourNote, sorted, detour, null);
            }
            // Only a bus reporting all along shows what it did; a gap in its reports hides it (tracking, not service).
            List<Phrase> notes = new ArrayList<>();
            Kind kind = Kind.NO_SIGNAL;
            if (!started) {
                Report first = sorted.get(0);
                Report before = before(byVehicle.get(first.vehicleId()), first.time());
                if (continuous(before, first)) {
                    kind = Kind.SHORT_TURNED;
                    notes.add(AnomalyNotes.joinedAt(trip, fromIndex));
                } else {
                    notes.add(AnomalyNotes.seenFrom(trip, fromIndex));
                }
            }
            if (!finished) {
                if (continuous(last, next)) {
                    kind = Kind.SHORT_TURNED;
                    notes.add(AnomalyNotes.leftAt(trip, toIndex));
                } else if (next != null && Objects.equals(next.brigade(), trip.brigade())) {
                    notes.add(AnomalyNotes.silentFrom(trip, toIndex));
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
                    notes.add(AnomalyNotes.lostAt(trip, toIndex));
                }
            }
            return abnormal(trip, kind, join(notes, changeNote, detourNote), sorted, detour, handover);
        }

        /**
         * Whether a bus that left a trip heading to stop {@code toIndex} and went on reporting ({@code next} the
         * report after its last on the trip) really turned short, near the end ({@link #SHORT_TURN_STOPS}): it went
         * on to its brigade's next trip (not to the depot), caught up at least {@link #SHORT_TURN_GAIN_SECONDS}, and
         * didn't drive on to the trip's last stop after all.
         */
        private boolean turnedShort(ScheduledTrip trip, int toIndex, Report last, Report next) {
            if (!Objects.equals(next.brigade(), trip.brigade()) || trip.size() - 1 - toIndex < SHORT_TURN_STOPS) {
                return false;
            }
            Instant until = last.time().plus(NEXT_DELAY_WITHIN);
            List<Report> afterwards = between(byVehicle.get(last.vehicleId()), next.time(), until);
            Integer before = last.usableDelay();
            Integer after = afterwards.stream().filter(r -> Objects.equals(r.brigade(), trip.brigade()))
                    .map(Report::usableDelay).filter(Objects::nonNull).findFirst().orElse(null);
            if (before == null || after == null || before - after < SHORT_TURN_GAIN_SECONDS) {
                return false;
            }
            return !reachedEnd(trip, last);
        }

        /**
         * Whether the bus came within {@link #REACHED_END_METERS} of the trip's last stop within
         * {@link #NEXT_DELAY_WITHIN} of its last report on the trip: it drove on to the end after all.
         */
        private boolean reachedEnd(ScheduledTrip trip, Report last) {
            return trip.lastStop() != null && between(byVehicle.get(last.vehicleId()), last.time(),
                    last.time().plus(NEXT_DELAY_WITHIN)).stream().anyMatch(r ->
                    Geo.meters(new double[] {r.lat(), r.lon()}, trip.lastStop()) <= REACHED_END_METERS);
        }

        /** Lists of phrases as one list. */
        @SafeVarargs
        private static List<Phrase> join(List<Phrase>... parts) {
            return Stream.of(parts).flatMap(List::stream).toList();
        }


        /**
         * A change of bus: who took over where, what became of the replaced bus (used again only once it reports a
         * brigade {@link #MOVED_METERS} away from where it left the trip), and what each bus did when.
         */
        private Handover handover(ScheduledTrip trip, Change c, List<Report> sorted) {
            Report a = c.fromLast();
            Report b = c.toFirst();
            List<Phrase> note = new ArrayList<>();
            note.add(AnomalyNotes.busChanged(a.vehicle(), b.vehicle(), trip, c.stoppedIndex(trip)));

            // Where the replacement came from: its last report on another brigade before.
            List<Report> bReports = byVehicle.getOrDefault(b.vehicleId(), List.of());
            Report origin = null;
            for (Report r : bReports) {
                if (!r.time().isBefore(b.time())) {
                    break;
                }
                if (hasBrigade(r) && !(r.brigade().equals(trip.brigade()) && r.route().equals(trip.route()))) {
                    origin = r;
                }
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
                fate = Fate.NOT_USED;
                stand = stand(c.stopped(), a, later);
                note.add(AnomalyNotes.notUsedAgain(a.vehicle()));
            } else if (used.brigade().equals(trip.brigade()) && used.route().equals(trip.route())) {
                fate = Fate.RETURNED;
                note.add(AnomalyNotes.backLater(a.vehicle()));
            } else {
                fate = Fate.REASSIGNED;
                note.add(AnomalyNotes.usedElsewhere(a.vehicle()));
            }

            // The buses' time lines.
            List<TripAnomalyDto.Span> spans = new ArrayList<>();
            Instant leftAt = stand == null ? a.time() : stand.until();
            spans.add(new TripAnomalyDto.Span(a.vehicle(), "running", c.fromFirst().time(), c.stopped().time()));
            spans.add(new TripAnomalyDto.Span(a.vehicle(), "standing", c.stopped().time(), leftAt));
            if (stand != null && stand.then() == Then.DROVE_OFF) {
                spans.add(new TripAnomalyDto.Span(a.vehicle(), "moving", stand.moved(), stand.lastSeen().time()));
            } else if (used != null) {
                spans.add(new TripAnomalyDto.Span(a.vehicle(), "service", used.time(), used.time()));
            }
            Report approach = approachStart(bReports, b, origin);
            if (approach != null) {
                spans.add(new TripAnomalyDto.Span(b.vehicle(), "approach", approach.time(), b.time()));
            }
            Instant serviceUntil = sorted.stream().filter(r -> r.vehicleId() == b.vehicleId())
                    .map(Report::time).max(Comparator.naturalOrder()).orElse(b.time());
            ScheduledTrip next = byBrigade.getOrDefault(trip.brigade(), List.of()).stream()
                    .filter(t -> t.departure().isAfter(trip.departure())).findFirst().orElse(null);
            List<Report> onNext = next == null ? List.of() : matched.getOrDefault(next, List.of()).stream()
                    .filter(r -> r.vehicleId() == b.vehicleId()).toList();
            if (!onNext.isEmpty()) {
                serviceUntil = onNext.get(onNext.size() - 1).time();
            }
            spans.add(new TripAnomalyDto.Span(b.vehicle(), "service", b.time(), serviceUntil));
            return new Handover(c, fate, stand, origin == null ? null : origin.route(),
                    origin == null ? null : origin.brigade(), List.copyOf(note),
                    spans.stream().filter(sp -> !sp.to().isBefore(sp.from())).toList());
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

        private Outcome abnormal(ScheduledTrip trip, Kind kind, List<Phrase> note, List<Report> reports,
                                 Detour detour, Handover handover) {
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
            TripAnomalyDto.Trip dto = new TripAnomalyDto.Trip(kind.key, note, trip.tripId(), trip.route(),
                    trip.brigade(), trip.headsign(), trip.stops()[0], trip.stops()[trip.size() - 1],
                    trip.departure(), trip.arrival(), trip.size(), vehicles, fromStop, toStop, coveredFrom, coveredTo,
                    reports.isEmpty() ? null : reports.get(0).time(),
                    reports.isEmpty() ? null : reports.get(reports.size() - 1).time(),
                    at.apply(0), delays.stream().max(Integer::compare).orElse(null),
                    at.apply(delays.size() - 1),
                    detour == null ? null : detour.meters(), detour == null ? null : detour.minutes(),
                    detour == null ? null : detour.from(), detour == null ? null : detour.to(),
                    detour == null ? List.of() : detour.path(), detour == null ? List.of() : detour.route(),
                    handover == null ? null : handover.fate().key, handover == null ? null : handover.fromRoute(),
                    handover == null ? null : handover.fromBrigade(),
                    handover == null ? null : handover.change().standMinutes(),
                    handover == null ? null : handover.change().delayBefore(),
                    handover == null ? null : handover.change().delayAfter(),
                    handover == null ? null : trip.stops()[handover.change().stoppedIndex(trip)],
                    handover == null ? null : trip.stops()[handover.change().toIndex()],
                    handover == null ? null : handover.change().skippedStops(trip),
                    stand(handover, Stand::minutes), stand(handover, st -> st.then().key),
                    stand(handover, st -> st.lastSeen().time()), stand(handover, Stand::metres),
                    handover == null ? List.of() : handover.spans());
            return new Outcome(trip.tripId(), trip.route(), trip.departure(), trip.arrival(), kind, dto,
                    vehicleIds(reports));
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
