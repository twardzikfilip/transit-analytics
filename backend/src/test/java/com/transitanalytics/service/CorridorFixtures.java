package com.transitanalytics.service;

import com.transitanalytics.service.CorridorBuilder.TripStops;
import com.transitanalytics.service.StopPlaces.StopRef;

import java.util.HashMap;
import java.util.Map;

/** Trips and stops for the corridor unit tests. */
final class CorridorFixtures {

    private long nextTrip = 1;

    /** A trip of a line through these stop ids, leaving the first stop at the given minute, one minute per stop. */
    TripStops trip(String line, int startMin, long... stops) {
        int[] departures = new int[stops.length];
        Double[] km = new Double[stops.length];
        for (int i = 0; i < stops.length; i++) {
            departures[i] = (startMin + i) * 60;
            km[i] = i * 0.5;
        }
        return new TripStops(nextTrip++, line, stops, departures, km);
    }

    /** Stops 1–99 named S<id>, each 1 km apart; stop 100 + n is the opposite platform of n (same name, 30 m away). */
    static Map<Long, StopRef> stops() {
        Map<Long, StopRef> stops = new HashMap<>();
        for (long id = 1; id < 100; id++) {
            stops.put(id, new StopRef("S" + id, 53.0 + id * 0.009, 23.0));
            stops.put(100 + id, new StopRef("S" + id, 53.0 + id * 0.009, 23.0004));
        }
        return stops;
    }
}
