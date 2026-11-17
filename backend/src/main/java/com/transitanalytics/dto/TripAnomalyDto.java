package com.transitanalytics.dto;

import java.time.Instant;
import java.util.List;

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
    public record Report(String window, Instant from, Instant to, Summary summary, List<LineCounts> lines,
                         int tripsTotal, List<Trip> trips) {
    }

    /**
     * Outcomes of the trips scheduled to run in the window.
     *
     * @param scheduled    all of them
     * @param judged       those with recordings throughout and over (or already abnormal)
     * @param completed    run in full, on the route
     * @param detours      run in full, but off the route for a while
     * @param noSignal     the bus stopped reporting partway (started reporting late, or came back only on its
     *                     brigade's next trip): most likely run, but unseen
     * @param shortTurned  run only partly: the bus, reporting all along, joined midway or left before the end
     * @param lost         the bus disappeared midway while recording went on
     * @param missed       not run, while the brigade's bus ran other trips that day
     * @param cancelled    not run, and no bus ran the brigade at all that day
     * @param notRecorded  recording had a gap during the trip (or its line couldn't be matched): not judged
     * @param pending      under way and not clear yet (or not due)
     */
    public record Summary(int scheduled, int judged, int completed, int detours, int noSignal, int shortTurned,
                          int lost, int missed, int cancelled, int notRecorded, int pending) {
    }

    /** Outcomes per line (judged trips). */
    public record LineCounts(String line, int judged, int completed, int detours, int noSignal, int shortTurned,
                             int lost, int missed, int cancelled) {
    }

    /**
     * An abnormal trip.
     *
     * @param kind          cancelled, missed, lost, short-turned, no-signal or detour
     * @param note          what was seen, in words: why it counts as abnormal and what the bus did instead
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
     */
    public record Trip(String kind, String note, String tripId, String line, String brigade, String headsign,
                       String firstStop, String lastStop, Instant scheduledDeparture, Instant scheduledArrival,
                       int stops, List<String> vehicles, Integer fromStop, Integer toStop, String coveredFrom,
                       String coveredTo, Instant firstSeen, Instant lastSeen, Integer startDelay, Integer maxDelay,
                       Integer endDelay, Integer detourMeters, Integer detourMinutes, String detourFrom,
                       String detourTo) {
    }
}
