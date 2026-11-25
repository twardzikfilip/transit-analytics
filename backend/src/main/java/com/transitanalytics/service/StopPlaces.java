package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.geo.Geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.transitanalytics.service.LineTimetable.*;
import static com.transitanalytics.service.Periods.*;
import static com.transitanalytics.service.RunTracker.*;
import static com.transitanalytics.service.TimetableMath.*;

/**
 * Stops as places: names without their stop code, positions, distance, and grouping stops less than 500 m apart
 * (a terminus's platforms) into one place.
 */
final class StopPlaces {

    static final Pattern STOP_CODE = Pattern.compile("\\s*\\(\\d+\\)\\s*$");

    /** Stops closer than this are one place: an end stop is often a pair of platforms or two sides of a loop. */
    static final double SAME_PLACE_METERS = 500;

    /** A stop by name, with its position when known. */
    record StopRef(String name, Double lat, Double lon) {
        boolean located() {
            return lat != null && lon != null;
        }
    }

    /** A group of nearby stops, named after the one where runs end most often. */
    record Place(String name, List<StopRef> stops) {
    }

    /** Whether two stops are one place: closer than {@link #SAME_PLACE_METERS}, or, without positions, same name. */
    static boolean samePlace(StopRef a, StopRef b) {
        if (a.located() && b.located()) {
            return meters(a.lat(), a.lon(), b.lat(), b.lon()) < SAME_PLACE_METERS;
        }
        return sameStop(a.name(), b.name());
    }

    /**
     * Every located bus stop of the timetable, with whether runs start or end there ({@code terminal}). Columns: name,
     * latitude, longitude, terminal.
     */
    static final String STOPS_WITH_TERMINALS = """
            WITH ends AS (
                SELECT trip_id, min(stop_sequence) AS first, max(stop_sequence) AS last
                FROM stop_time GROUP BY trip_id),
            terminals AS (
                SELECT DISTINCT st.stop_id
                FROM stop_time st JOIN ends e ON e.trip_id = st.trip_id
                WHERE st.stop_sequence IN (e.first, e.last))
            SELECT s.name, s.latitude, s.longitude, t.stop_id IS NOT NULL AS terminal
            FROM stop s LEFT JOIN terminals t ON t.stop_id = s.id
            WHERE s.latitude IS NOT NULL AND s.longitude IS NOT NULL AND s.feed <> '%s'""".formatted(TimetableFeed.RAIL);

    /** Distance in metres (see {@link Geo#meters}). */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        return Geo.meters(new double[] {lat1, lon1}, new double[] {lat2, lon2});
    }

    /**
     * Whether a stop is one of the places' stops. Only the stops themselves count, not others nearby: a stop 400 m
     * from a terminus where no run turns round is still somewhere else.
     */
    static boolean atPlace(List<Place> places, StopRef stop) {
        return stop.name() != null
                && places.stream().anyMatch(p -> p.stops().stream().anyMatch(s -> s.name().equals(stop.name())));
    }

    /**
     * Groups stops into places: stops all closer than {@link #SAME_PLACE_METERS} to each other are one place. A place is named after its stop where runs end most often (an arrival), else where they start most
     * often. Places are sorted by name.
     *
     * @param arrivals   stops where runs end, once per run
     * @param departures stops where runs start, once per run
     */
    static List<Place> places(List<StopRef> arrivals, List<StopRef> departures) {
        Map<String, StopRef> distinct = new LinkedHashMap<>();
        Map<String, Integer> arriving = new HashMap<>();
        Map<String, Integer> departing = new HashMap<>();
        java.util.function.Function<StopRef, String> key = s -> s.name() + "@" + s.lat() + "," + s.lon();
        arrivals.stream().filter(s -> s.name() != null).forEach(s -> {
            distinct.putIfAbsent(key.apply(s), s);
            arriving.merge(key.apply(s), 1, Integer::sum);
        });
        departures.stream().filter(s -> s.name() != null).forEach(s -> {
            distinct.putIfAbsent(key.apply(s), s);
            departing.merge(key.apply(s), 1, Integer::sum);
        });

        // A stop joins a group only if it is close to every stop already in it, so places can't creep along a street.
        List<List<StopRef>> groups = new ArrayList<>();
        for (StopRef stop : distinct.values()) {
            groups.stream().filter(g -> g.stream().allMatch(s -> samePlace(s, stop))).findFirst()
                    .ifPresentOrElse(g -> g.add(stop), () -> groups.add(new ArrayList<>(List.of(stop))));
        }

        return groups.stream().map(group -> {
            Map<String, int[]> byName = new LinkedHashMap<>();
            for (StopRef s : group) {
                int[] counts = byName.computeIfAbsent(s.name(), n -> new int[2]);
                counts[0] += arriving.getOrDefault(key.apply(s), 0);
                counts[1] += departing.getOrDefault(key.apply(s), 0);
            }
            String name = byName.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> e.getValue()[0])
                            .thenComparingInt(e -> e.getValue()[1])
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .orElseThrow().getKey();
            return new Place(name, group);
        }).sorted(Comparator.comparing(Place::name)).toList();
    }

    /**
     * Whether two stop names are the same place: equal once platform numbers ("st. 4") are dropped, or one is the
     * other with a street in front ("Ciołkowskiego/Chorten Arena" and "Chorten Arena").
     */
    static boolean sameStop(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = PLATFORM.matcher(a.toLowerCase(java.util.Locale.ROOT)).replaceAll("").strip();
        String y = PLATFORM.matcher(b.toLowerCase(java.util.Locale.ROOT)).replaceAll("").strip();
        return x.equals(y) || x.endsWith("/" + y) || y.endsWith("/" + x);
    }

    static final Pattern PLATFORM = Pattern.compile("\\s+st\\.\\s*\\d+$");

    /** The stop name without the stop code the feed appends, e.g. "Lodowa/Chłodna(654)" → "Lodowa/Chłodna". */
    static String stopName(String name) {
        return name == null ? null : STOP_CODE.matcher(name).replaceFirst("");
    }
}
