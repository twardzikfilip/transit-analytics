package com.transitanalytics.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abnormal trips: scheduled trips of the timetable that weren't run, or not in full, or not on their route, from
 * recorded positions matched to the timetable by brigade and scheduled time.
 */
public final class TripAnomalyDto {

    private TripAnomalyDto() {
    }

    /**
     * @param summary     every trip scheduled to run (for any part) in the window, by outcome
     * @param lines       the same per line, lines with anomalies first
     * @param tripsTotal  abnormal trips in the window; {@code trips} lists the newest {@code trips.size()} of them
     * @param trips       abnormal trips, the latest scheduled departure first
     */
    public record Report(Instant from, Instant to, Summary summary, List<LineCounts> lines,
                         int tripsTotal, List<Trip> trips) {
    }

    /**
     * Outcomes of the trips scheduled to run in the window.
     *
     * @param scheduled    all of them
     * @param judged       those with recordings throughout and over (or already abnormal)
     * @param completed    run in full, on the route
     * @param detours      run in full, but off the route for a while
     * @param vehicleChanged run in full, but by two buses: one ran part of it and another of the brigade took over
     * @param noSignal     the bus stopped reporting partway (started reporting late, or came back only on its
     *                     brigade's next trip): most likely run, but unseen
     * @param shortTurned  run only partly: the bus, reporting all along, joined midway, or turned back at least 3
     *                     stops before the end onto its brigade's next trip, catching up 3 min or more
     * @param lost         the bus disappeared midway while recording went on
     * @param missed       not run, while the brigade's bus ran other trips that day
     * @param cancelled    not run, and no bus ran the brigade at all that day
     * @param notRecorded  recording had a gap during the trip (or its line couldn't be matched): not judged
     * @param pending      under way and not clear yet (or not due)
     * @param changedBrokenDown of the bus changes, those whose replaced bus wasn't used again that day
     * @param changedReassigned of the bus changes, those whose replaced bus was used later on another line or brigade
     */
    public record Summary(int scheduled, int judged, int completed, int detours, int vehicleChanged, int noSignal,
                          int shortTurned, int lost, int missed, int cancelled, int notRecorded, int pending,
                          int changedBrokenDown, int changedReassigned) {
    }

    /** Outcomes per line (judged trips). */
    public record LineCounts(String line, int judged, int completed, int detours, int vehicleChanged,
                             int noSignal, int shortTurned, int lost, int missed, int cancelled) {
    }

    /**
     * An abnormal trip.
     *
     * @param kind          cancelled, missed, lost, short-turned, vehicle-changed, no-signal or detour
     * @param note          what was seen, in phrases: why it counts as abnormal and what the bus did instead
     * @param stops         stops on the trip
     * @param vehicles      buses seen on it, in order
     * @param fromStop      first stop it was seen at or after (1-based), null when not run
     * @param toStop        last stop it was seen heading to (1-based)
     * @param startDelay    the feed's delay at its first report on the trip (seconds)
     * @param maxDelay      the highest delay on the trip
     * @param endDelay      the delay at its last report
     * @param detourMeters  how far it went from the route at most
     * @param detourMinutes how long it was off the route
     * @param detourFrom    the stop before the detour, and {@code detourTo} the next stop it reported after
     * @param detourPath    on a detour, the bus's path {lat, lon}: its reports off the route, with a few either side
     * @param detourRoute   …and the usual route around it, {lat, lon}
     * @param changeFate    on a change of bus, what became of the replaced bus: broken-down (not used again that
     *                      day), reassigned (used on another line or brigade) or returned (back on the brigade)
     * @param replacementLine the line the replacement bus ran before taking over, and {@code replacementBrigade}
     *                      its brigade; null when it hadn't run another brigade that day
     * @param changeMinutes on a change of bus, how long the trip stood still: from the replaced bus stopping for good
     *                      to the replacement's first report
     * @param delayBeforeChange the replaced bus's delay when it stopped, and {@code delayAfterChange} the
     *                      replacement's first
     * @param changeStop    the stop the replaced bus was heading to when it stopped, and {@code joinStop} the one the
     *                      replacement was first heading to
     * @param skippedStops  stops between the two that neither bus was seen heading to; 0 when the replacement took over
     *                      at the same place
     * @param changeAt      on a change of bus, when the replaced bus stopped for good
     * @param stoodMinutes  when the replaced bus wasn't used again: how long it stood where it stopped
     * @param stoodThen     …and what it did next: vanished (stopped reporting while recording went on), drove-off
     *                      (moved away without running a brigade) or still-there (standing at the last report)
     * @param stoodLastSeen its last report that day, and {@code stoodLastSeenMeters} how far that was from where it
     *                      stopped
     * @param story         on a change of bus, what happened step by step, in time order
     * @param timeline      on a change of bus, what each bus did when, for a time line
     */
    public record Trip(String kind, List<Phrase> note, String tripId, String line, String brigade, String headsign,
                       String firstStop, String lastStop, Instant scheduledDeparture, Instant scheduledArrival,
                       int stops, List<String> vehicles, Integer fromStop, Integer toStop, String coveredFrom,
                       String coveredTo, Instant firstSeen, Instant lastSeen, Integer startDelay, Integer maxDelay,
                       Integer endDelay, Integer detourMeters, Integer detourMinutes, String detourFrom,
                       String detourTo, List<double[]> detourPath, List<double[]> detourRoute, String changeFate,
                       String replacementLine, String replacementBrigade,
                       Integer changeMinutes, Integer delayBeforeChange, Integer delayAfterChange, String changeStop,
                       String joinStop, Integer skippedStops, Instant changeAt, Integer stoodMinutes,
                       String stoodThen, Instant stoodLastSeen, Integer stoodLastSeenMeters, List<Step> story,
                       List<Span> timeline) {
    }

    /**
     * Buses whose tracker probably fails, over a recent stretch of days.
     *
     * @param minDays      a bus is listed with no-signal trips on at least this many days, {@code minTrips} of them
     *                     or more, and at least {@code minSharePercent} of the trips it ran
     * @param trackers     most no-signal trips first
     */
    public record FaultyTrackers(Instant from, Instant to, int minDays, int minTrips, int minSharePercent,
                                 List<FaultyTracker> trackers) {
    }

    /**
     * A bus whose tracker probably fails.
     *
     * @param noSignalTrips trips it ran with parts missing (it stopped reporting and was back later)
     * @param trips         trips it ran in all (in full or in part)
     * @param days          days with such trips
     * @param lastNoSignal  the scheduled departure of the latest one
     * @param lines         the lines they were on
     */
    public record FaultyTracker(long vehicleId, String bus, int noSignalTrips, int trips, int days,
                                Instant lastNoSignal, List<String> lines) {
    }

    /** One step of what happened at a change of bus, in phrases. */
    public record Step(Instant at, List<Phrase> text) {
    }

    /**
     * A sentence for the page to write in the reader's language: a text key (under {@code tripNotes} in the
     * frontend's translations) and the values it needs. A value is text, a number (the page formats it), or another
     * phrase (a part of the sentence, such as "the brigade's next trip").
     */
    public record Phrase(String key, Map<String, Object> params) {

        /** A phrase from its key and its values as name, value pairs: {@code of("lost", "bus", "046")}. */
        public static Phrase of(String key, Object... namesAndValues) {
            if (namesAndValues.length % 2 != 0) {
                throw new IllegalArgumentException("Names and values come in pairs: " + key);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                params.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
            return new Phrase(key, Collections.unmodifiableMap(params));
        }
    }

    /**
     * A stretch of a bus's time around a change of bus.
     *
     * @param kind running (the replaced bus on the trip), standing (it stood where it stopped), moving (it moved
     *             off without a brigade), approach (the replacement on its way, without the brigade) or service (on a
     *             brigade: the replacement on the trip and on, or the replaced bus used again, as a moment)
     */
    public record Span(String vehicle, String kind, Instant from, Instant to) {
    }
}
