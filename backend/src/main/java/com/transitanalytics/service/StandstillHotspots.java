package com.transitanalytics.service;

import com.transitanalytics.dto.HotspotDto.Approach;
import com.transitanalytics.dto.HotspotDto.Hotspot;
import com.transitanalytics.dto.HotspotDto.LineTime;
import com.transitanalytics.geo.Geo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static com.transitanalytics.service.TimetableMath.percentile;
import static com.transitanalytics.service.TimetableMath.round;

/**
 * Standstills away from stops and the places where they gather. A standstill is a vehicle on a line reporting twice
 * or more in a row within a few metres; the ones near a stop (boarding) or a terminal (layovers) are left out, and the
 * rest are clustered with DBSCAN into hotspots.
 */
final class StandstillHotspots {

    /** Standstills this close to a stop are boarding (an articulated bus is 18 m long, and buses queue at stops). */
    static final double STOP_METERS = 50;
    /** Or this close to a terminal: layovers, often in a loop or bay away from the stop itself. */
    static final double TERMINAL_METERS = 150;
    /** DBSCAN: standstills this close are neighbours… */
    static final double EPS_METERS = 25;
    /** …and a standstill with this many neighbours (itself included) is the core of a hotspot. */
    static final int MIN_POINTS = 5;
    /** A hotspot needs this many different vehicles, so one bus parked repeatedly doesn't make one. */
    static final int MIN_VEHICLES = 3;
    private static final String[] HEADINGS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    /** Approaches below this share of a hotspot's time aren't listed. */
    private static final double MIN_APPROACH_SHARE = 0.1;

    private StandstillHotspots() {
    }

    /** A vehicle standing still: from its first report there, for {@code seconds}, heading {@code bearing}. */
    record Standstill(long vehicleId, String line, Instant start, double seconds, double lat, double lon,
                      Integer bearing) {
    }

    /** A stop, and whether runs start or end there. */
    record StopPoint(String name, double lat, double lon, boolean terminal) {
    }

    /** The standstills farther than {@link #STOP_METERS} from every stop and {@link #TERMINAL_METERS} from terminals. */
    static List<Standstill> awayFromStops(List<Standstill> standstills, List<StopPoint> stops) {
        Grid<StopPoint> grid = new Grid<>(stops, StopPoint::lat, StopPoint::lon, TERMINAL_METERS);
        return standstills.stream().filter(s -> grid.near(s.lat(), s.lon(), TERMINAL_METERS).stream().noneMatch(p -> {
            double m = StopPlaces.meters(s.lat(), s.lon(), p.lat(), p.lon());
            return m < STOP_METERS || (p.terminal() && m < TERMINAL_METERS);
        })).toList();
    }

    /** DBSCAN clusters (noise left out), with {@link #EPS_METERS} and {@link #MIN_POINTS}. */
    static List<List<Standstill>> clusters(List<Standstill> points) {
        Grid<Standstill> grid = new Grid<>(points, Standstill::lat, Standstill::lon, EPS_METERS);
        final int unvisited = 0;
        final int noise = -1;
        int[] label = new int[points.size()];
        int[] near = new int[points.size()];
        int[] queue = new int[points.size()];
        int cluster = 0;
        for (int i = 0; i < points.size(); i++) {
            if (label[i] != unvisited) {
                continue;
            }
            int found = grid.near(points.get(i).lat(), points.get(i).lon(), EPS_METERS, near);
            if (found < MIN_POINTS) {
                label[i] = noise;
                continue;
            }
            cluster++;
            label[i] = cluster;
            // Points are labelled when they join the queue, so each is in it once.
            int queued = enqueue(near, found, label, cluster, queue, 0);
            while (queued > 0) {
                Standstill p = points.get(queue[--queued]);
                int more = grid.near(p.lat(), p.lon(), EPS_METERS, near);
                if (more >= MIN_POINTS) {
                    queued = enqueue(near, more, label, cluster, queue, queued);
                }
            }
        }
        Map<Integer, List<Standstill>> byCluster = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            if (label[i] > 0) {
                byCluster.computeIfAbsent(label[i], c -> new ArrayList<>()).add(points.get(i));
            }
        }
        return new ArrayList<>(byCluster.values());
    }

    /**
     * Labels the points found with the cluster unless they are in one, and queues those not looked at yet (a point
     * taken for noise before is a border point: its neighbours are too few).
     *
     * @return how many are queued now
     */
    private static int enqueue(int[] found, int count, int[] label, int cluster, int[] queue, int queued) {
        int size = queued;
        for (int k = 0; k < count; k++) {
            int j = found[k];
            if (label[j] == 0) {
                queue[size++] = j;
            }
            if (label[j] <= 0) {
                label[j] = cluster;
            }
        }
        return size;
    }

    /** The hotspots, the most time lost first; clusters with fewer than {@link #MIN_VEHICLES} vehicles are left out. */
    static List<Hotspot> hotspots(List<Standstill> standstills, List<StopPoint> stops, double days) {
        List<List<Standstill>> clusters = clusters(standstills).stream()
                .filter(c -> c.stream().map(Standstill::vehicleId).distinct().count() >= MIN_VEHICLES)
                .sorted(Comparator.comparingDouble(StandstillHotspots::seconds).reversed())
                .toList();
        Grid<StopPoint> stopGrid = new Grid<>(stops, StopPoint::lat, StopPoint::lon, 500);
        List<Hotspot> hotspots = new ArrayList<>();
        for (List<Standstill> c : clusters) {
            hotspots.add(hotspot(hotspots.size() + 1, c, stopGrid, stops, days));
        }
        return hotspots;
    }

    static double seconds(List<Standstill> standstills) {
        return standstills.stream().mapToDouble(Standstill::seconds).sum();
    }

    private static Hotspot hotspot(int id, List<Standstill> c, Grid<StopPoint> stopGrid, List<StopPoint> stops,
                                   double days) {
        double total = seconds(c);
        double lat = c.stream().mapToDouble(s -> s.lat() * s.seconds()).sum() / total;
        double lon = c.stream().mapToDouble(s -> s.lon() * s.seconds()).sum() / total;
        double[] distances = c.stream().mapToDouble(s -> StopPlaces.meters(lat, lon, s.lat(), s.lon())).sorted()
                .toArray();
        double[] durations = c.stream().mapToDouble(Standstill::seconds).sorted().toArray();

        List<StopPoint> candidates = stopGrid.near(lat, lon, 500);
        StopPoint nearest = (candidates.isEmpty() ? stops : candidates).stream()
                .min(Comparator.comparingDouble(p -> StopPlaces.meters(lat, lon, p.lat(), p.lon()))).orElse(null);

        List<LineTime> lines = c.stream().collect(Collectors.groupingBy(Standstill::line,
                        Collectors.summingDouble(Standstill::seconds))).entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(TimetableMath.LINE_ORDER)))
                .map(e -> new LineTime(e.getKey(), round(e.getValue() / 3600, 2))).toList();

        double[] byHeading = new double[HEADINGS.length];
        double withBearing = 0;
        double[] byHour = new double[24];
        for (Standstill s : c) {
            if (s.bearing() != null) {
                byHeading[heading(s.bearing())] += s.seconds();
                withBearing += s.seconds();
            }
            byHour[s.start().atZone(ServiceDay.ZONE).getHour()] += s.seconds();
        }
        List<Approach> approaches = new ArrayList<>();
        for (int h = 0; h < HEADINGS.length && withBearing > 0; h++) {
            if (byHeading[h] / withBearing >= MIN_APPROACH_SHARE) {
                approaches.add(new Approach(HEADINGS[h], round(byHeading[h] / withBearing, 2)));
            }
        }
        approaches.sort(Comparator.comparingDouble(Approach::share).reversed());
        int peakHour = 0;
        for (int h = 1; h < 24; h++) {
            if (byHour[h] > byHour[peakHour]) {
                peakHour = h;
            }
        }

        return new Hotspot(id, round(lat, 6), round(lon, 6), (int) Math.round(percentile(distances, 0.9)),
                round(total / 3600, 2), round(total / 60 / days, 1), c.size(),
                (int) c.stream().map(Standstill::vehicleId).distinct().count(),
                (int) Math.round(total / c.size()), (int) Math.round(percentile(durations, 0.5)),
                (int) Math.round(percentile(durations, 0.9)),
                nearest == null ? null : StopPlaces.stopName(nearest.name()),
                nearest == null ? 0 : (int) Math.round(StopPlaces.meters(lat, lon, nearest.lat(), nearest.lon())),
                lines, approaches, peakHour);
    }

    /** The compass sector (0 = N, 1 = NE, …) of a bearing in degrees. */
    static int heading(int bearing) {
        return Math.floorMod((int) Math.round(bearing / 45.0), 8);
    }

    /** Points in square cells of a fixed size, for finding the ones near a place without comparing with all. */
    static final class Grid<T> {
        private final List<T> items;
        private final double[] lats;
        private final double[] lons;
        private final double cell;
        private final double mPerDegLon;
        private final Map<Long, int[]> cells = new HashMap<>();

        Grid(List<T> items, ToDoubleFunction<T> lat, ToDoubleFunction<T> lon, double cellMeters) {
            this.items = items;
            this.lats = items.stream().mapToDouble(lat).toArray();
            this.lons = items.stream().mapToDouble(lon).toArray();
            this.cell = cellMeters;
            double meanLat = items.stream().mapToDouble(lat).average().orElse(53);
            this.mPerDegLon = Geo.M_PER_DEG * Math.cos(Math.toRadians(meanLat));
            Map<Long, List<Integer>> byCell = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                byCell.computeIfAbsent(key(cx(lons[i]), cy(lats[i])), k -> new ArrayList<>()).add(i);
            }
            byCell.forEach((k, list) -> cells.put(k, list.stream().mapToInt(Integer::intValue).toArray()));
        }

        private long cx(double lonDeg) {
            return (long) Math.floor(lonDeg * mPerDegLon / cell);
        }

        private long cy(double latDeg) {
            return (long) Math.floor(latDeg * Geo.M_PER_DEG / cell);
        }

        private static long key(long x, long y) {
            return x * 1_000_003L + y;
        }

        /** Indexes of the items within {@code meters} (at most the cell size) of a place. */
        List<Integer> nearIndexes(double latDeg, double lonDeg, double meters) {
            int candidates = 0;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    int[] cellItems = cells.get(key(cx(lonDeg) + dx, cy(latDeg) + dy));
                    candidates += cellItems == null ? 0 : cellItems.length;
                }
            }
            if (candidates == 0) {
                return List.of();
            }
            int[] found = new int[candidates];
            int count = near(latDeg, lonDeg, meters, found);
            return Arrays.stream(found, 0, count).boxed().toList();
        }

        /**
         * As {@link #nearIndexes}, into {@code found} (with room for the items of the cells around).
         *
         * @return how many there are
         */
        int near(double latDeg, double lonDeg, double meters, int[] found) {
            long x = cx(lonDeg);
            long y = cy(latDeg);
            int count = 0;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    int[] cellItems = cells.get(key(x + dx, y + dy));
                    if (cellItems == null) {
                        continue;
                    }
                    for (int i : cellItems) {
                        if (within(latDeg, lonDeg, i, meters)) {
                            found[count++] = i;
                        }
                    }
                }
            }
            return count;
        }

        /** Whether an item is within {@code meters}; the exact distance (slow) only where it's close to that. */
        private boolean within(double latDeg, double lonDeg, int i, double meters) {
            double east = (lons[i] - lonDeg) * Geo.M_PER_DEG * Geo.COS_LAT;
            double north = (lats[i] - latDeg) * Geo.M_PER_DEG;
            double square = east * east + north * north;
            double limit = meters * meters;
            if (Math.abs(square - limit) > limit * 1e-9) {
                return square < limit;
            }
            return StopPlaces.meters(latDeg, lonDeg, lats[i], lons[i]) <= meters;
        }

        List<T> near(double latDeg, double lonDeg, double meters) {
            return nearIndexes(latDeg, lonDeg, meters).stream().map(items::get).toList();
        }
    }
}
