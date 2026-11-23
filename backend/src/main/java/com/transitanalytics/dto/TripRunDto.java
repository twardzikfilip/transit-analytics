package com.transitanalytics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Trips scheduled in a time window, each with what ran: the bus, its delay and speed along the way, where it stood. */
public final class TripRunDto {

    private TripRunDto() {
    }

    /**
     * A page of the trips scheduled to run (for any part) in a window, in departure order.
     *
     * @param shownFrom the start of what is listed: the window's, or {@link #MAX_HOURS} before its end when longer
     * @param total     trips in the window matching the search
     * @param page      0-based; {@code size} trips a page
     */
    public record Page(Instant from, Instant to, Instant shownFrom, String q, int total, int page, int size,
                       List<Trip> trips) {

        /** The longest stretch listed: the last day of a longer window. */
        public static final int MAX_HOURS = 24;
    }

    /**
     * A scheduled trip and how it ran.
     *
     * @param serviceDay the day it belongs to (trips after midnight belong to the day before)
     * @param trip       the trip, its outcome and what ran, as on the brigade's day
     * @param samples    the bus's delay and speed while it ran the trip, about every minute, in time order
     * @param stands     where a bus stood still on the way for {@code STAND_MINUTES} or more (not at the trip's ends)
     * @param stops      the trip's stops in order, when each was scheduled and when the bus passed it
     * @param averageKmh its buses' average speed over the trip: the way they drove over the time it took, stops and
     *                   waits included; null when not seen running
     */
    public record Trip(LocalDate serviceDay, String brigade, BrigadeDto.Trip trip, List<Sample> samples,
                       List<Stand> stands, List<StopTime> stops, Integer averageKmh) {
    }

    /**
     * A stop of the trip.
     *
     * @param index     its number on the trip (1-based)
     * @param scheduled when the bus was due to leave it
     * @param passed    when a bus was first seen heading beyond it (for the last stop: the last bus's arrival, when
     *                  it got there); null when no bus was seen past it
     * @param delay     the feed's delay then (seconds), null when unknown
     */
    public record StopTime(int index, String name, Instant scheduled, Instant passed, Integer delay) {
    }

    /**
     * Where a bus was on the trip at a moment.
     *
     * @param delay seconds late (negative: early), null when the feed gave none
     * @param kmh   its speed since the previous sample, null for the first or a GPS jump
     * @param stop  the stop (1-based) it was heading to
     */
    public record Sample(Instant at, String bus, Integer delay, Integer kmh, int stop) {
    }

    /**
     * A bus standing still on the trip.
     *
     * @param stop        the stop it was heading to, {@code stopIndex} its number (1-based)
     * @param delayGrowth how much later it was when it moved on (seconds), null when unknown
     */
    public record Stand(String bus, Instant from, Instant to, int minutes, String stop, int stopIndex,
                        Integer delayGrowth, double lat, double lon) {
    }

    /**
     * One trip in detail, asked for when it is opened: every report of its buses while they ran it, with how late
     * and how fast they were, its route, and its stops with how long the bus stood at each.
     *
     * @param points the reports in time order
     * @param route  the trip's route shape, {lat, lon}; empty when the timetable has none
     * @param stops  the trip's stops in order, with where they are
     */
    public record Detail(String tripId, List<DetailPoint> points, List<double[]> route, List<DetailStop> stops) {
    }

    /**
     * A stop of the trip: its number on the trip (1-based), name and place.
     *
     * @param dwellSeconds how long a bus halted within {@code AT_STOP_METRES} of it, roughly (reports come about
     *                     every 10 s); 0 when it clearly went past without stopping; null when that can't be told
     *                     (not seen near it, or moving slowly there)
     */
    public record DetailStop(int index, String name, double lat, double lon, Integer dwellSeconds) {
    }

    /**
     * A bus's report on the trip.
     *
     * @param delay seconds late (null when the feed gave none)
     * @param kmh   its speed then: 0 standing still, else over the half minute around it; null for its first report,
     *              after a gap, or a GPS jump
     */
    public record DetailPoint(Instant at, String bus, double lat, double lon, Integer delay, Integer kmh) {
    }
}
