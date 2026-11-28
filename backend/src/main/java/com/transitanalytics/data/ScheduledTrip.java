package com.transitanalytics.data;

import java.time.Instant;
import java.util.Arrays;

/**
 * A trip of the timetable on a service day: its stops (sequence numbers, names, and scheduled departures in
 * seconds after {@code departure}).
 */
public record ScheduledTrip(String tripId, String route, String brigade, String headsign, Instant departure,
                            Instant arrival, int[] seqs, String[] stops, int[] offsets, String shapeId,
                            double[] lastStop) {

    /** A trip whose last stop's position ([latitude, longitude]) is unknown (layovers there aren't told apart). */
    public ScheduledTrip(String tripId, String route, String brigade, String headsign, Instant departure,
                         Instant arrival, int[] seqs, String[] stops, int[] offsets, String shapeId) {
        this(tripId, route, brigade, headsign, departure, arrival, seqs, stops, offsets, shapeId, null);
    }

    public int size() {
        return seqs.length;
    }

    /** The position (0-based) of a stop sequence number on the trip; the nearest one when it isn't on it. */
    public int index(int seq) {
        int i = Arrays.binarySearch(seqs, seq);
        return i >= 0 ? i : Math.min(seqs.length - 1, Math.max(0, -i - 1));
    }

    /** When the bus is scheduled to leave the stop with this sequence number. */
    public Instant scheduledAt(int seq) {
        return departure.plusSeconds(offsets[index(seq)]);
    }
}
