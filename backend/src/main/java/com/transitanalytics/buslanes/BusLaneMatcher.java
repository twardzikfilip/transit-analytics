package com.transitanalytics.buslanes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the bus lane a bus is on: within {@code meters} of one and heading its way (within {@code maxAngle}
 * degrees). Lanes are split into directed pieces (one per direction with a lane), kept in a grid for quick lookup.
 */
public final class BusLaneMatcher {

    /** Grid cells, metres. */
    private static final double CELL = 100;
    /** Route shapes are walked in steps of at most this many metres. */
    static final double STEP = 20;

    /** Which lane (index into the lanes), and whether in its direction. */
    public record Match(int lane, boolean forward) {
    }

    /**
     * A route's bus lanes.
     *
     * @param meters     the route's length
     * @param laneMeters how much of it runs on bus lanes
     * @param onLanes    the parts on bus lanes, [latitude, longitude] in order
     */
    public record Share(double meters, double laneMeters, List<List<double[]>> onLanes) {
    }

    private record Piece(double ax, double ay, double bx, double by, double heading, Match match) {
    }

    private final double meters;
    private final double maxAngle;
    private final Map<Long, List<Piece>> grid = new HashMap<>();

    public BusLaneMatcher(List<BusLane> lanes, double meters, double maxAngle) {
        this.meters = meters;
        this.maxAngle = maxAngle;
        for (int i = 0; i < lanes.size(); i++) {
            BusLane lane = lanes.get(i);
            List<double[]> pts = lane.points();
            for (int j = 1; j < pts.size(); j++) {
                double[] a = pts.get(j - 1);
                double[] b = pts.get(j);
                if (lane.forward() > 0) {
                    add(a, b, new Match(i, true));
                }
                if (lane.backward() > 0) {
                    add(b, a, new Match(i, false));
                }
            }
        }
    }

    private void add(double[] a, double[] b, Match match) {
        double[] p = Geo.xy(a[0], a[1]);
        double[] q = Geo.xy(b[0], b[1]);
        Piece piece = new Piece(p[0], p[1], q[0], q[1], Geo.heading(a, b), match);
        long x0 = cell(Math.min(p[0], q[0]) - meters);
        long x1 = cell(Math.max(p[0], q[0]) + meters);
        long y0 = cell(Math.min(p[1], q[1]) - meters);
        long y1 = cell(Math.max(p[1], q[1]) + meters);
        for (long x = x0; x <= x1; x++) {
            for (long y = y0; y <= y1; y++) {
                grid.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(piece);
            }
        }
    }

    /** The lane at a point ([latitude, longitude]) for a bus heading that way (degrees from north); null: none. */
    public Match match(double lat, double lon, double heading) {
        return match(lat, lon, heading, meters);
    }

    /**
     * The same within {@code within} metres (up to twice the matcher's distance; e.g. to reach the lane on the far
     * carriageway).
     */
    public Match match(double lat, double lon, double heading, double within) {
        double radius = Math.min(within, 2 * meters);
        double[] p = Geo.xy(lat, lon);
        Match best = null;
        double nearest = radius;
        long cx = cell(p[0]);
        long cy = cell(p[1]);
        // Pieces are in every cell within the matcher's distance of them; further needs the neighbouring cells too
        // (a piece found twice is just measured twice).
        int around = radius > meters ? 1 : 0;
        for (long x = cx - around; x <= cx + around; x++) {
            for (long y = cy - around; y <= cy + around; y++) {
                List<Piece> pieces = grid.get(key(x, y));
                if (pieces == null) {
                    continue;
                }
                for (Piece piece : pieces) {
                    if (Geo.angle(heading, piece.heading()) > maxAngle) {
                        continue;
                    }
                    double d = toSegment(p[0], p[1], piece);
                    if (d <= nearest) {
                        nearest = d;
                        best = piece.match();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Per lane: whether buses have a lane the other way too, on the section itself or alongside it (the other
     * carriageway of a dual carriageway, within twice the matching distance) for at least half its length.
     */
    public boolean[] bothWays(List<BusLane> lanes) {
        boolean[] both = new boolean[lanes.size()];
        for (int i = 0; i < lanes.size(); i++) {
            BusLane lane = lanes.get(i);
            if (lane.forward() > 0 && lane.backward() > 0) {
                both[i] = true;
                continue;
            }
            List<double[]> path = new ArrayList<>(lane.points());
            if (lane.forward() == 0) {
                java.util.Collections.reverse(path);
            }
            double total = 0;
            double paired = 0;
            for (int j = 1; j < path.size(); j++) {
                double[] a = path.get(j - 1);
                double[] b = path.get(j);
                double length = Geo.meters(a, b);
                int steps = Math.max(1, (int) Math.ceil(length / STEP));
                double heading = Geo.heading(a, b) + 180;
                for (int s = 0; s < steps; s++) {
                    double[] mid = lerp(a, b, (s + 0.5) / steps);
                    total += length / steps;
                    if (match(mid[0], mid[1], heading, 2 * meters) != null) {
                        paired += length / steps;
                    }
                }
            }
            both[i] = total > 0 && paired >= total / 2;
        }
        return both;
    }

    /** How much of a route shape ([latitude, longitude] in order) runs on bus lanes, and where. */
    public Share share(List<double[]> shape) {
        double total = 0;
        double onLane = 0;
        List<List<double[]>> parts = new ArrayList<>();
        List<double[]> current = null;
        for (int i = 1; i < shape.size(); i++) {
            double[] a = shape.get(i - 1);
            double[] b = shape.get(i);
            double length = Geo.meters(a, b);
            if (length == 0) {
                continue;
            }
            double heading = Geo.heading(a, b);
            int steps = (int) Math.ceil(length / STEP);
            for (int s = 0; s < steps; s++) {
                double[] from = lerp(a, b, (double) s / steps);
                double[] to = lerp(a, b, (double) (s + 1) / steps);
                double[] mid = lerp(a, b, (s + 0.5) / steps);
                double step = length / steps;
                total += step;
                if (match(mid[0], mid[1], heading) != null) {
                    onLane += step;
                    if (current == null) {
                        current = new ArrayList<>();
                        current.add(from);
                        parts.add(current);
                    }
                    current.add(to);
                } else {
                    current = null;
                }
            }
        }
        return new Share(total, onLane, parts);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private static double toSegment(double x, double y, Piece s) {
        double dx = s.bx() - s.ax();
        double dy = s.by() - s.ay();
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - s.ax()) * dx + (y - s.ay()) * dy) / len));
        return Math.hypot(x - s.ax() - t * dx, y - s.ay() - t * dy);
    }

    private static long cell(double metres) {
        return (long) Math.floor(metres / CELL);
    }

    private static long key(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }
}
