package com.transitanalytics.service;

import com.transitanalytics.geo.Geo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.transitanalytics.service.TripAnomalies.*;
import static com.transitanalytics.service.TripHandovers.*;

/**
 * Part of {@link TripAnomalies}: route shapes in metres, how far a bus is from its route, and detours.
 */
final class TripShapes {

    private TripShapes() {
    }

    // ---- Route shapes ---------------------------------------------------------------------------------------

    /** A route shape in metres (a flat projection, fine across a city). */
    record Shape(double[] x, double[] y) {
    }

    /** A shape from its points, {lat, lon} in order. */
    static Shape shape(List<double[]> points) {
        double[] x = new double[points.size()];
        double[] y = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            double[] xy = Geo.xy(points.get(i)[0], points.get(i)[1]);
            x[i] = xy[0];
            y[i] = xy[1];
        }
        return new Shape(x, y);
    }

    /** Distance in metres from a point to the nearest part of a shape. */
    static double distance(Shape shape, double lat, double lon) {
        return new Tracker(shape).distance(lat, lon);
    }

    /**
     * Distances from a bus's successive positions to a shape. A bus moves along its route, so the segments near the
     * last nearest one are tried first; the whole shape only when the bus seems off it.
     */
    static final class Tracker {
        /** Segments either side of the last nearest one tried first. */
        private static final int NEAR = 25;
        private final Shape shape;
        private int last = -1;

        Tracker(Shape shape) {
            this.shape = shape;
        }

        double distance(double lat, double lon) {
            double[] p = Geo.xy(lat, lon);
            double px = p[0];
            double py = p[1];
            int segments = shape.x().length - 1;
            if (segments < 1) {
                return Math.sqrt(squared(px - shape.x()[0], py - shape.y()[0]));
            }
            if (last >= 0) {
                double near = nearest(px, py, Math.max(0, last - NEAR), Math.min(segments, last + NEAR + 1));
                if (near <= OFF_ROUTE_METERS * OFF_ROUTE_METERS) {
                    return Math.sqrt(near);
                }
            }
            return Math.sqrt(nearest(px, py, 0, segments));
        }

        /** The squared distance to the nearest of segments {@code [from, to)}; remembers which one it was. */
        private double nearest(double px, double py, int from, int to) {
            double[] x = shape.x();
            double[] y = shape.y();
            double best = Double.MAX_VALUE;
            for (int i = from; i < to; i++) {
                double dx = x[i + 1] - x[i];
                double dy = y[i + 1] - y[i];
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x[i]) * dx + (py - y[i]) * dy) / len2));
                double d = squared(px - (x[i] + t * dx), py - (y[i] + t * dy));
                if (d < best) {
                    best = d;
                    last = i;
                }
            }
            return best;
        }

        private static double squared(double dx, double dy) {
            return dx * dx + dy * dy;
        }
    }

    /** The longest detour of a run: reports off the route, in a row, while between its first and last stops. */
    record Detour(int meters, int minutes, String from, String to, List<double[]> path, List<double[]> route) {
    }

    /** Reports either side of a detour shown with it, to see where the bus left the route and came back. */
    static final int DETOUR_CONTEXT_REPORTS = 3;
    /** The usual route is shown this far around the detour's path (metres). */
    static final double DETOUR_ROUTE_MARGIN_METERS = 600;

    static Detour detour(ScheduledTrip trip, List<Report> reports, Shape shape) {
        if (shape == null) {
            return null;
        }
        Detour longest = null;
        int longestLength = 0;
        List<Report> run = new ArrayList<>();
        int runStart = 0;
        double runMax = 0;
        Report previous = null;
        Tracker tracker = new Tracker(shape);
        for (int i = 0; i < reports.size(); i++) {
            Report r = reports.get(i);
            boolean between = r.nextSeq() != null && trip.index(r.nextSeq()) >= 1
                    && trip.index(r.nextSeq()) <= trip.size() - 2;
            double d = between ? tracker.distance(r.lat(), r.lon()) : 0;
            boolean continues = previous != null && previous.vehicleId() == r.vehicleId()
                    && Duration.between(previous.time(), r.time()).compareTo(DETOUR_REPORT_GAP) <= 0;
            if (d > OFF_ROUTE_METERS && (run.isEmpty() || continues)) {
                if (run.isEmpty()) {
                    runStart = i;
                }
                run.add(r);
                runMax = Math.max(runMax, d);
            } else {
                if (run.size() >= DETOUR_REPORTS && run.size() > longestLength) {
                    longest = detourOf(trip, run, runMax, reports, runStart, shape);
                    longestLength = run.size();
                }
                run = new ArrayList<>();
                runMax = 0;
                if (d > OFF_ROUTE_METERS) {
                    runStart = i;
                    run.add(r);
                    runMax = d;
                }
            }
            previous = r;
        }
        if (run.size() >= DETOUR_REPORTS && run.size() > longestLength) {
            longest = detourOf(trip, run, runMax, reports, runStart, shape);
        }
        return longest;
    }

    /**
     * A detour from its run of off-route reports (starting at {@code runStart} in {@code reports}): how far and long,
     * between which stops, the bus's path (with a few reports either side, of the same bus) and the usual route
     * around it, both {lat, lon}.
     */
    private static Detour detourOf(ScheduledTrip trip, List<Report> run, double maxMeters, List<Report> reports,
                                   int runStart, Shape shape) {
        int start = trip.index(run.get(0).nextSeq());
        int end = trip.index(run.get(run.size() - 1).nextSeq());
        long seconds = Duration.between(run.get(0).time(), run.get(run.size() - 1).time()).toSeconds() + 20;
        long bus = run.get(0).vehicleId();
        int from = runStart;
        while (from > 0 && runStart - from < DETOUR_CONTEXT_REPORTS && reports.get(from - 1).vehicleId() == bus) {
            from--;
        }
        int runEnd = runStart + run.size() - 1;
        int to = runEnd;
        while (to + 1 < reports.size() && to - runEnd < DETOUR_CONTEXT_REPORTS
                && reports.get(to + 1).vehicleId() == bus) {
            to++;
        }
        List<double[]> path = reports.subList(from, to + 1).stream()
                .map(r -> new double[] {round5(r.lat()), round5(r.lon())}).toList();
        return new Detour((int) Math.round(maxMeters), (int) Math.max(1, Math.round(seconds / 60.0)),
                trip.stops()[Math.max(0, start - 1)], trip.stops()[end], path, around(shape, path));
    }

    /**
     * The stretch of a route shape near a path: from its first to its last point within
     * {@link #DETOUR_ROUTE_MARGIN_METERS} of the path's bounding box, {lat, lon}.
     */
    static List<double[]> around(Shape shape, List<double[]> path) {
        double margin = DETOUR_ROUTE_MARGIN_METERS;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : path) {
            double[] xy = Geo.xy(p[0], p[1]);
            double x = xy[0];
            double y = xy[1];
            minX = Math.min(minX, x - margin);
            maxX = Math.max(maxX, x + margin);
            minY = Math.min(minY, y - margin);
            maxY = Math.max(maxY, y + margin);
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < shape.x().length; i++) {
            if (shape.x()[i] >= minX && shape.x()[i] <= maxX && shape.y()[i] >= minY && shape.y()[i] <= maxY) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        List<double[]> route = new ArrayList<>();
        for (int i = Math.max(0, first); first >= 0 && i <= last; i++) {
            double[] point = Geo.latLon(shape.x()[i], shape.y()[i]);
            route.add(new double[] {round5(point[0]), round5(point[1])});
        }
        return route;
    }

    /** Five decimals of a degree: about a metre. */
    private static double round5(double degrees) {
        return Math.round(degrees * 100_000) / 100_000.0;
    }
}
