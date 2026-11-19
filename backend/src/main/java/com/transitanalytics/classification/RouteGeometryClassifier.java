package com.transitanalytics.classification;

import com.transitanalytics.domain.RouteGeometry;
import com.transitanalytics.geo.Geo;

import java.util.List;

/**
 * A line's geometry from its route shape and the city centre, by the rules in {@code classification/routes.yml}.
 * Coordinates are {latitude, longitude}; distances use a flat projection, fine across a city.
 */
public final class RouteGeometryClassifier {


    private final RouteClassProperties.Geometry rules;
    private final double cosLat;
    /** The centre in metres (x east, y north), and its middle. */
    private final double[][] centre;
    private final double midX;
    private final double midY;
    /** The central termini in metres. */
    private final List<double[]> termini;

    public RouteGeometryClassifier(RouteClassProperties.Geometry rules, double[][] centre) {
        this.rules = rules;
        this.cosLat = Math.cos(Math.toRadians(centre[0][0]));
        this.centre = new double[centre.length][];
        double sx = 0;
        double sy = 0;
        int n = centre.length - 1; // the ring repeats its first point
        for (int i = 0; i < centre.length; i++) {
            this.centre[i] = xy(centre[i]);
            if (i < n) {
                sx += this.centre[i][0];
                sy += this.centre[i][1];
            }
        }
        this.midX = sx / n;
        this.midY = sy / n;
        this.termini = rules.centralTermini().stream()
                .map(t -> xy(new double[] {t.latitude(), t.longitude()})).toList();
    }

    /**
     * What was measured: how far the shape came to the centre, its ends' distances, the angle between them, how far
     * it swings round the centre (the widest range of bearings it covers, seen from the middle of the centre, in
     * degrees; more than the angle between the ends for a shape that turns back), and whether an end is at a
     * central terminus.
     */
    public record Measures(double closestToCentre, double firstEndToCentre, double lastEndToCentre,
                           double angleBetweenEnds, double sweep, boolean endsAtCentralTerminus) {
    }

    /** The geometry of a shape (in order); null for a shape of fewer than two points. */
    public RouteGeometry classify(List<double[]> shape) {
        Measures m = measure(shape);
        if (m == null) {
            return null;
        }
        if (m.closestToCentre() <= rules.centreMarginMeters()) {
            if (Math.min(m.firstEndToCentre(), m.lastEndToCentre()) <= rules.terminusMarginMeters()
                    || m.endsAtCentralTerminus()) {
                return RouteGeometry.RADIAL;
            }
            if (m.angleBetweenEnds() >= rules.diameterMinAngle()) {
                return RouteGeometry.DIAMETER;
            }
            return m.angleBetweenEnds() >= rules.arcantialMinAngle() ? RouteGeometry.ARCANTIAL : RouteGeometry.RADIAL;
        }
        return m.sweep() >= rules.tangentialMinAngle() ? RouteGeometry.TANGENTIAL : RouteGeometry.FEEDER;
    }

    /** The measures the rules use; null for a shape of fewer than two points. */
    public Measures measure(List<double[]> shape) {
        if (shape == null || shape.size() < 2) {
            return null;
        }
        double closest = Double.MAX_VALUE;
        double turned = 0;
        double least = 0;
        double most = 0;
        Double previous = null;
        for (double[] p : shape) {
            double[] point = xy(p);
            closest = Math.min(closest, toCentre(point));
            double now = bearing(point);
            if (previous != null) {
                turned += ((now - previous) % 360 + 540) % 360 - 180; // the change, within ±180°
                least = Math.min(least, turned);
                most = Math.max(most, turned);
            }
            previous = now;
        }
        double[] a = xy(shape.get(0));
        double[] b = xy(shape.get(shape.size() - 1));
        double angle = Math.abs(bearing(a) - bearing(b));
        if (angle > 180) {
            angle = 360 - angle;
        }
        return new Measures(closest, toCentre(a), toCentre(b), angle, most - least,
                atCentralTerminus(a) || atCentralTerminus(b));
    }

    private boolean atCentralTerminus(double[] p) {
        return termini.stream()
                .anyMatch(t -> Math.hypot(p[0] - t[0], p[1] - t[1]) <= rules.centralTerminusMarginMeters());
    }

    private double[] xy(double[] latLon) {
        return new double[] {latLon[1] * Geo.M_PER_DEG * cosLat, latLon[0] * Geo.M_PER_DEG};
    }

    /** Degrees clockwise from north, seen from the middle of the centre. */
    private double bearing(double[] p) {
        return Math.toDegrees(Math.atan2(p[0] - midX, p[1] - midY));
    }

    /** Metres from a point to the centre: 0 inside it, else to its nearest edge. */
    private double toCentre(double[] p) {
        if (Geo.inside(p, centre)) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < centre.length; i++) {
            best = Math.min(best, Geo.toSegment(p, centre[i], centre[i + 1]));
        }
        return best;
    }
}
