package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.LineDetailDto.Brigade;
import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.dto.LineDetailDto.Variant;
import com.transitanalytics.dto.LineDetailDto.Zone2;
import com.transitanalytics.dto.LineDetailDto;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.transitanalytics.service.Periods.*;
import static com.transitanalytics.service.RunTracker.*;
import static com.transitanalytics.service.StopPlaces.*;
import static com.transitanalytics.service.TimetableMath.*;

/**
 * One line's weekday timetable: its trips and variants (the base route of each direction, longer and shorter ones,
 * depot runs), the standard end stops, depot runs, brigades and the part in fare zone 2.
 */
@org.springframework.stereotype.Component
public class LineTimetable {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final WeekdayTimetable weekdayTimetable;

    public LineTimetable(org.springframework.jdbc.core.JdbcTemplate jdbc, WeekdayTimetable weekdayTimetable) {
        this.jdbc = jdbc;
        this.weekdayTimetable = weekdayTimetable;
    }

    /** A gap between two runs of a brigade of at least this long is a break, not just a layover at the terminus. */
    static final int BREAK_SECONDS = 30 * 60;
    /** A variant counts as longer than the base route when it is this much longer. */
    static final double LONGER_MARGIN_KM = 0.2;

    /** A timetable trip of the line. Times are seconds after midnight. */
    record TripRow(long id, String duty, int direction, String shapeId, String headsign, int start, int end,
                   Double km, int stops, String firstStop, String lastStop, StopRef first, StopRef last) {
        TripRow(long id, String duty, int direction, String shapeId, String headsign, int start, int end,
                Double km, int stops, String firstStop, String lastStop) {
            this(id, duty, direction, shapeId, headsign, start, end, km, stops, firstStop, lastStop,
                    new StopRef(firstStop, null, null), new StopRef(lastStop, null, null));
        }

        int duration() {
            return end - start;
        }
    }

    /**
     * @param endStops standard end stops (termini), as places of nearby stops
     * @param depot    "pull_out" or "pull_in" by trip id, for depot runs
     */
    record Timetable(String service, List<TripRow> trips, List<Variant> variants, List<Place> endStops,
                             Map<Long, String> depot) {
        Map<Integer, Variant> mains() {
            Map<Integer, Variant> mains = new TreeMap<>();
            variants.stream().filter(v -> v.kind().equals("main")).forEach(v -> mains.put(v.direction(), v));
            return mains;
        }
    }

    Timetable timetable(Route route, boolean withShapes) {
        String service = weekdayTimetable.serviceId();
        if (service == null) {
            return new Timetable(null, List.of(), List.of(), List.of(), Map.of());
        }
        List<TripRow> trips = jdbc.query("""
                SELECT t.id, %s AS duty,
                       coalesce(t.direction, 0) AS direction, t.shape_id, t.headsign,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s,
                       max(st.shape_dist_traveled) AS km, count(*) AS stops,
                       (array_agg(s.name ORDER BY st.stop_sequence))[1] AS first_stop,
                       (array_agg(s.name ORDER BY st.stop_sequence DESC))[1] AS last_stop,
                       (array_agg(s.latitude ORDER BY st.stop_sequence))[1] AS first_lat,
                       (array_agg(s.longitude ORDER BY st.stop_sequence))[1] AS first_lon,
                       (array_agg(s.latitude ORDER BY st.stop_sequence DESC))[1] AS last_lat,
                       (array_agg(s.longitude ORDER BY st.stop_sequence DESC))[1] AS last_lon
                FROM trip t JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE t.route_id = ? AND t.service_id = ?
                GROUP BY t.id
                ORDER BY t.id""".formatted(ServiceDay.BRIGADE_SQL),
                (rs, i) -> new TripRow(rs.getLong("id"), rs.getString("duty"), rs.getInt("direction"),
                        rs.getString("shape_id"), rs.getString("headsign"), rs.getInt("start_s"), rs.getInt("end_s"),
                        rs.getObject("km") == null ? null : rs.getDouble("km"), rs.getInt("stops"),
                        stopName(rs.getString("first_stop")), stopName(rs.getString("last_stop")),
                        new StopRef(stopName(rs.getString("first_stop")), (Double) rs.getObject("first_lat"),
                                (Double) rs.getObject("first_lon")),
                        new StopRef(stopName(rs.getString("last_stop")), (Double) rs.getObject("last_lat"),
                                (Double) rs.getObject("last_lon"))),
                route.getId(), service);

        // Variants: one per direction and shape. The base route of a direction is its most frequent shape.
        Map<String, List<TripRow>> byVariant = trips.stream().collect(Collectors.groupingBy(
                t -> variantKey(t.direction(), t.shapeId()), LinkedHashMap::new, Collectors.toList()));
        Map<Integer, String> mainShape = new HashMap<>();
        trips.stream().collect(Collectors.groupingBy(TripRow::direction)).forEach((dir, list) ->
                mainShape.put(dir, TimetableMath.mostFrequent(list.stream().map(TripRow::shapeId).toList())));

        // Stops of each variant from its trip with the most stops.
        Map<String, TripRow> representative = new LinkedHashMap<>();
        byVariant.forEach((key, list) -> representative.put(key, list.stream()
                .max(Comparator.comparingInt(TripRow::stops).thenComparing(TripRow::id, Comparator.reverseOrder()))
                .orElseThrow()));
        Map<Long, List<StopPoint>> stopsByTrip = stops(representative.values().stream().map(TripRow::id).toList());
        Map<String, List<double[]>> shapes = withShapes
                ? shapes(byVariant.values().stream().map(l -> l.get(0).shapeId()).filter(Objects::nonNull).toList())
                : Map.of();

        // Termini: where buses turn round in service, plus the base routes' ends. Depot runs start or end elsewhere.
        List<Place> endStops = endStops(trips, trips.stream()
                .filter(t -> Objects.equals(t.shapeId(), mainShape.get(t.direction()))).toList());
        Map<Long, String> depot = depotRuns(trips, endStops);

        Map<Integer, Double> mainKm = new HashMap<>();
        byVariant.forEach((key, list) -> {
            TripRow first = list.get(0);
            if (Objects.equals(first.shapeId(), mainShape.get(first.direction()))) {
                mainKm.put(first.direction(), maxKm(list));
            }
        });

        List<Variant> variants = new ArrayList<>();
        byVariant.forEach((key, list) -> {
            TripRow first = list.get(0);
            double km = maxKm(list);
            long depotTrips = list.stream().filter(t -> depot.containsKey(t.id())).count();
            String kind = Objects.equals(first.shapeId(), mainShape.get(first.direction())) ? "main"
                    : depotTrips * 2 >= list.size() ? "depot"
                    : km > mainKm.getOrDefault(first.direction(), 0.0) + LONGER_MARGIN_KM ? "longer" : "shorter";
            List<Double> speeds = list.stream().filter(t -> t.km() != null && t.duration() > 0)
                    .map(t -> t.km() / (t.duration() / 3600.0)).toList();
            List<StopPoint> stops = stopsByTrip.getOrDefault(representative.get(key).id(), List.of());
            List<double[]> points = !withShapes ? List.of()
                    : shapes.containsKey(first.shapeId()) ? shapes.get(first.shapeId())
                    : stops.stream().filter(s -> s.lat() != null && s.lon() != null)
                            .map(s -> new double[]{s.lat(), s.lon()}).toList();
            variants.add(new Variant(first.direction(), first.shapeId(),
                    TimetableMath.mostFrequent(list.stream().map(TripRow::headsign).toList()), kind,
                    TimetableMath.round(km, 2), list.size(),
                    list.stream().mapToInt(t -> t.duration() / 60).min().orElse(0),
                    list.stream().mapToInt(t -> t.duration() / 60).max().orElse(0),
                    speeds.stream().min(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                    speeds.stream().max(Double::compare).map(v -> TimetableMath.round(v, 1)).orElse(null),
                    stops.isEmpty() ? null : stops.get(0).name(),
                    stops.isEmpty() ? null : stops.get(stops.size() - 1).name(), points, stops));
        });
        List<String> kindOrder = List.of("main", "longer", "shorter", "depot");
        variants.sort(Comparator.comparingInt(Variant::direction)
                .thenComparingInt(v -> kindOrder.indexOf(v.kind()))
                .thenComparing(Variant::km, Comparator.reverseOrder()));
        return new Timetable(service, trips, variants, endStops, depot);
    }

    static List<TripRow> mainTrips(Timetable tt) {
        Map<Integer, Variant> mains = tt.mains();
        return tt.trips().stream()
                .filter(t -> mains.containsKey(t.direction())
                        && Objects.equals(mains.get(t.direction()).shapeId(), t.shapeId()))
                .toList();
    }

    private Map<Long, List<StopPoint>> stops(List<Long> tripIds) {
        Map<Long, List<StopPoint>> result = new HashMap<>();
        if (tripIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT st.trip_id, s.name, s.latitude, s.longitude, st.shape_dist_traveled, s.zone_id
                FROM stop_time st JOIN stop s ON s.id = st.stop_id
                WHERE st.trip_id IN (%s)
                ORDER BY st.trip_id, st.stop_sequence""".formatted(placeholders(tripIds.size())),
                rs -> {
                    result.computeIfAbsent(rs.getLong("trip_id"), k -> new ArrayList<>()).add(new StopPoint(
                            stopName(rs.getString("name")),
                            rs.getObject("latitude") == null ? null : rs.getDouble("latitude"),
                            rs.getObject("longitude") == null ? null : rs.getDouble("longitude"),
                            TimetableMath.round(rs.getDouble("shape_dist_traveled"), 3), rs.getString("zone_id")));
                },
                tripIds.toArray());
        return result;
    }

    private Map<String, List<double[]>> shapes(List<String> shapeIds) {
        Map<String, List<double[]>> result = new HashMap<>();
        List<String> distinct = shapeIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT shape_id, latitude, longitude FROM shape_point
                WHERE shape_id IN (%s) ORDER BY shape_id, sequence""".formatted(placeholders(distinct.size())),
                rs -> {
                    result.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>()).add(new double[]{
                            TimetableMath.round(rs.getDouble("latitude"), 5),
                            TimetableMath.round(rs.getDouble("longitude"), 5)});
                },
                distinct.toArray());
        return result;
    }

    static List<LineDetailDto.EndStop> toDto(List<Place> places) {
        return places.stream().map(p -> new LineDetailDto.EndStop(p.name(), p.stops().stream().map(StopRef::name)
                .filter(n -> !n.equals(p.name())).distinct().sorted().toList())).toList();
    }

    /**
     * The part of a route in fare zone 2: its stops there, and its distance there. The zone border isn't in the feed,
     * so a segment between a zone-1 and a zone-2 stop counts half. Null when the route has no stop in zone 2.
     */
    static Zone2 zone2(int direction, String headsign, String kind, List<StopPoint> stops) {
        int count = (int) stops.stream().filter(LineTimetable::inZone2).count();
        if (count == 0) {
            return null;
        }
        double km = 0;
        for (int i = 1; i < stops.size(); i++) {
            double segment = stops.get(i).km() - stops.get(i - 1).km();
            int ends = (inZone2(stops.get(i - 1)) ? 1 : 0) + (inZone2(stops.get(i)) ? 1 : 0);
            km += segment * ends / 2.0;
        }
        double total = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).km() - stops.get(0).km();
        return new Zone2(direction, headsign, kind, count, stops.size(), TimetableMath.round(km, 1),
                TimetableMath.round(total, 1));
    }

    static boolean inZone2(StopPoint s) {
        return "2".equals(s.zone());
    }

    /**
     * The day's brigades: each bus's runs in order, from leaving the depot (first departure) to returning to it (last
     * arrival), with the gaps between runs of at least {@link #BREAK_SECONDS} as breaks.
     *
     * @param kinds variant kind by {@link #variantKey}
     * @param depot "pull_out" or "pull_in" by trip id, for depot runs
     */
    static List<Brigade> brigades(List<TripRow> trips, Map<String, String> kinds, Map<Long, String> depot) {
        List<Brigade> brigades = new ArrayList<>();
        byDuty(trips).forEach((duty, list) -> {
            List<LineDetailDto.Run> runs = list.stream().map(t -> new LineDetailDto.Run(t.start(), t.end(),
                    t.direction(), t.headsign(), kinds.getOrDefault(variantKey(t.direction(), t.shapeId()), "main"),
                    depot.get(t.id()))).toList();
            List<LineDetailDto.Break> breaks = new ArrayList<>();
            for (int i = 1; i < list.size(); i++) {
                int from = list.get(i - 1).end();
                int to = list.get(i).start();
                if (to - from >= BREAK_SECONDS) {
                    breaks.add(new LineDetailDto.Break(from, to));
                }
            }
            brigades.add(new Brigade(duty, list.get(0).start(),
                    list.stream().mapToInt(TripRow::end).max().orElseThrow(), runs, breaks));
        });
        return brigades;
    }

    static String variantKey(int direction, String shapeId) {
        return direction + "|" + shapeId;
    }

    /** Each brigade's runs in time order. */
    static Map<String, List<TripRow>> byDuty(List<TripRow> trips) {
        Map<String, List<TripRow>> byDuty = new TreeMap<>();
        trips.stream().filter(t -> t.duty() != null)
                .forEach(t -> byDuty.computeIfAbsent(t.duty(), k -> new ArrayList<>()).add(t));
        byDuty.values().forEach(l -> l.sort(Comparator.comparingInt(TripRow::start).thenComparingInt(TripRow::end)));
        return byDuty;
    }

    /**
     * Standard end stops: where buses turn round in service. A run's last stop is one when the same brigade starts
     * its next run within {@link #BREAK_SECONDS}, and so is that next run's first stop; the base routes' ends always
     * are. Stops closer than {@link #SAME_PLACE_METERS} are one end stop, named after the stop where runs end. A bus
     * waiting longer at an end stop is on a break there, not at the depot.
     *
     * @param mainTrips runs of the base routes, whose ends always count
     */
    static List<Place> endStops(List<TripRow> trips, List<TripRow> mainTrips) {
        List<StopRef> arrivals = new ArrayList<>();
        List<StopRef> departures = new ArrayList<>();
        for (TripRow t : mainTrips) {
            arrivals.add(t.last());
            departures.add(t.first());
        }
        for (List<TripRow> runs : byDuty(trips).values()) {
            for (int i = 1; i < runs.size(); i++) {
                if (runs.get(i).start() - runs.get(i - 1).end() < BREAK_SECONDS) {
                    arrivals.add(runs.get(i - 1).last());
                    departures.add(runs.get(i).first());
                }
            }
        }
        return places(arrivals, departures);
    }

    /**
     * Depot runs by trip id: "pull_out" for a run that starts away from the end stops at the start of the brigade's day
     * or after a break of {@link #BREAK_SECONDS} or more; "pull_in" for one that ends away from them at the end of the
     * day or before such a break. A short turn to another stop in the middle of service is neither.
     */
    static Map<Long, String> depotRuns(List<TripRow> trips, List<Place> endStops) {
        Map<Long, String> depot = new HashMap<>();
        for (List<TripRow> runs : byDuty(trips).values()) {
            for (int i = 0; i < runs.size(); i++) {
                TripRow t = runs.get(i);
                boolean afterBreak = i == 0 || t.start() - runs.get(i - 1).end() >= BREAK_SECONDS;
                boolean beforeBreak = i == runs.size() - 1 || runs.get(i + 1).start() - t.end() >= BREAK_SECONDS;
                if (afterBreak && !atPlace(endStops, t.first())) {
                    depot.put(t.id(), "pull_out");
                } else if (beforeBreak && !atPlace(endStops, t.last())) {
                    depot.put(t.id(), "pull_in");
                }
            }
        }
        return depot;
    }

    static double maxKm(List<TripRow> trips) {
        return trips.stream().map(TripRow::km).filter(Objects::nonNull).mapToDouble(d -> d).max().orElse(0);
    }

    static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
