package com.transitanalytics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A brigade's (vehicle duty's) runs on one service day, from the timetable. */
public final class BrigadeDto {

    private BrigadeDto() {
    }

    /**
     * @param brigade    e.g. 103-05, as live feeds name it
     * @param serviceDay the day the runs belong to; runs after midnight belong to the day before
     * @param runs       in departure order; empty when the brigade doesn't run that day or is not in the timetable
     */
    public record Schedule(String brigade, LocalDate serviceDay, List<Run> runs) {
    }

    /** One trip of the brigade, from its first stop to its last. */
    public record Run(String line, String headsign, String from, String to, Instant departure, Instant arrival) {
    }

    /**
     * Every brigade in the timetable, whatever day it runs, for picking one.
     *
     * @param agency    the agency they are of (its timetable id, e.g. BKM)
     * @param agencies  every agency in the timetable; one whose timetable doesn't name brigades has none
     */
    public record BrigadeList(String agency, List<Agency> agencies, List<BrigadeRef> brigades) {
    }

    /** An agency to pick: its timetable id and name. */
    public record Agency(String id, String name) {
    }

    /**
     * A brigade to pick.
     *
     * @param lines the lines it runs, on any day
     */
    public record BrigadeRef(String brigade, List<String> lines) {
    }

    /**
     * A brigade's service day: each trip scheduled, and what actually ran, from the recorded positions.
     *
     * @param trips in departure order
     */
    public record Day(String brigade, LocalDate serviceDay, Summary summary, List<Trip> trips) {
    }

    /**
     * How the day went.
     *
     * @param judged       trips over and recorded (the rest are upcoming or weren't recorded)
     * @param onTime       of the judged trips with a departure delay, those leaving between 1 min early and 3 min late
     * @param medianStartDelay seconds, over the trips run
     * @param buses        the buses that ran it, in order of first use
     */
    public record Summary(int trips, int judged, int completed, int abnormal, int onTime, int withStartDelay,
                          Integer medianStartDelay, Integer maxDelay, List<String> buses) {
    }

    /**
     * A scheduled trip and how it ran.
     *
     * @param direction 0 or 1 (by destination); alternate trips of a brigade usually alternate
     * @param kind      the trip's outcome, as on Abnormal trips: completed, detour, vehicle-changed, no-signal,
     *                  short-turned, lost, missed, cancelled, not-recorded, pending
     * @param note      what happened, in phrases, for an abnormal trip
     * @param runs      what ran, one per bus in order (more than one: the bus was changed)
     * @param missing   parts of the trip nobody ran, in scheduled time: before, between and after the runs (a trip
     *                  not run at all is missing whole)
     */
    public record Trip(String tripId, String line, String headsign, String from, String to, int direction,
                       Instant departure, Instant arrival, int stops, String kind, List<TripAnomalyDto.Phrase> note,
                       List<ActualRun> runs,
                       List<Missing> missing) {
    }

    /**
     * Part of a trip one bus ran: from the first stop it was seen heading to, to the last.
     *
     * @param fromStop   the first stop (1-based) it ran, {@code toStop} the last
     * @param departure  when it left: the scheduled time at {@code fromStop} plus its delay then (the report's time
     *                   when the feed gave no delay); {@code arrival} likewise at {@code toStop}
     * @param startDelay seconds late (negative: early) when it set off; {@code endDelay} at its last report
     */
    public record ActualRun(String bus, int fromStop, int toStop, String fromName, String toName, Instant departure,
                            Instant arrival, Integer startDelay, Integer endDelay) {
    }

    /**
     * Part of a trip missing from what ran, in scheduled time, between two of its stops (1-based).
     *
     * @param why "not-run" (lost, missed, cancelled or turned short) or "not-seen" (the bus stopped reporting, but
     *            probably ran it)
     */
    public record Missing(int fromStop, int toStop, Instant from, Instant to, String why) {
    }
}
