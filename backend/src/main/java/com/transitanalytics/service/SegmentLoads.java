package com.transitanalytics.service;

import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.service.StopPlaces.StopRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.transitanalytics.service.CorridorBuilder.*;
import static com.transitanalytics.service.Periods.*;
import static com.transitanalytics.service.StopPlaces.*;
import static com.transitanalytics.service.StretchStats.*;
import static com.transitanalytics.service.TimetableMath.*;

/** Every stretch between two stops with its buses and route shape, for the corridor maps. */
final class SegmentLoads {

    /** A point of a route shape: position and distance along the shape (km). */
    record ShapePoint(double lat, double lon, Double km) {
    }

    /**
     * Every stretch between two consecutive stops with its weekday buses, in peak hours and all day, for the maps.
     * Both directions are one stretch when their stops have the same names; the line follows the busier direction's
     * route shape (a straight line when the feed has no shape).
     */
    static List<CorridorDto.SegmentLoad> segmentLoads(List<TripStops> trips, Map<Long, StopRef> stops,
                                                       Map<String, List<ShapePoint>> shapes) {
        // Directed stretches: buses in peak, all day, and one trip (with its stop index) to take the shape from.
        record Directed(long from, long to, int[] counts, TripStops trip, int at) {
        }
        Map<List<Long>, Directed> directed = new LinkedHashMap<>();
        for (TripStops t : trips) {
            for (int i = 1; i < t.stops().length; i++) {
                int at = i - 1;
                Directed d = directed.computeIfAbsent(List.of(t.stops()[at], t.stops()[i]),
                        k -> new Directed(k.get(0), k.get(1), new int[2], t, at));
                int departure = t.departures()[at];
                if (Periods.in(PEAK, departure)) {
                    d.counts()[0]++;
                }
                d.counts()[1]++;
            }
        }
        // Both directions of a stretch together, keyed by its two stop names in either order.
        Map<String, List<Directed>> byNames = new LinkedHashMap<>();
        for (Directed d : directed.values()) {
            String a = stops.get(d.from()).name();
            String b = stops.get(d.to()).name();
            if (a.equals(b)) {
                continue;
            }
            byNames.computeIfAbsent(a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a,
                    k -> new ArrayList<>()).add(d);
        }
        List<CorridorDto.SegmentLoad> loads = new ArrayList<>();
        for (List<Directed> group : byNames.values()) {
            Directed busiest = group.stream().max(Comparator.comparingInt(d -> d.counts()[1])).orElseThrow();
            loads.add(new CorridorDto.SegmentLoad(stops.get(busiest.from()).name(), stops.get(busiest.to()).name(),
                    group.stream().mapToInt(d -> d.counts()[0]).sum(), group.stream().mapToInt(d -> d.counts()[1]).sum(),
                    shapeBetween(busiest.trip(), busiest.at(), stops, shapes)));
        }
        loads.sort(Comparator.comparingInt(CorridorDto.SegmentLoad::peakBuses));
        return loads;
    }

    /** The route between a trip's stop {@code at} and the next one: its shape cut at the two stops' distances. */
    static List<double[]> shapeBetween(TripStops trip, int at, Map<Long, StopRef> stops,
                                       Map<String, List<ShapePoint>> shapes) {
        StopRef a = stops.get(trip.stops()[at]);
        StopRef b = stops.get(trip.stops()[at + 1]);
        List<double[]> points = new ArrayList<>();
        if (a.located()) {
            points.add(new double[]{TimetableMath.round(a.lat(), 5), TimetableMath.round(a.lon(), 5)});
        }
        Double from = trip.km()[at];
        Double to = trip.km()[at + 1];
        List<ShapePoint> shape = trip.shapeId() == null ? null : shapes.get(trip.shapeId());
        if (shape != null && from != null && to != null) {
            for (ShapePoint p : shape) {
                if (p.km() != null && p.km() > from && p.km() < to) {
                    points.add(new double[]{TimetableMath.round(p.lat(), 5), TimetableMath.round(p.lon(), 5)});
                }
            }
        }
        if (b.located()) {
            points.add(new double[]{TimetableMath.round(b.lat(), 5), TimetableMath.round(b.lon(), 5)});
        }
        return points;
    }
}
