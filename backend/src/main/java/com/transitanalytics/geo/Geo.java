package com.transitanalytics.geo;

/**
 * Distances, headings and shapes across a city: a flat projection around Białystok's latitude, in metres east
 * ({@code x}) and north ({@code y}). Points are {@code [latitude, longitude]}.
 */
public final class Geo {

    public static final double M_PER_DEG = 111_320;
    public static final double COS_LAT = Math.cos(Math.toRadians(53.13));

    private Geo() {
    }

    /** [latitude, longitude] to metres {east, north}. */
    public static double[] xy(double lat, double lon) {
        return new double[] {lon * M_PER_DEG * COS_LAT, lat * M_PER_DEG};
    }

    /** Metres {east, north} back to [latitude, longitude]. */
    public static double[] latLon(double x, double y) {
        return new double[] {y / M_PER_DEG, x / (M_PER_DEG * COS_LAT)};
    }

    public static double meters(double[] a, double[] b) {
        return Math.hypot((b[1] - a[1]) * M_PER_DEG * COS_LAT, (b[0] - a[0]) * M_PER_DEG);
    }

    /** Degrees clockwise from north, from a to b ([latitude, longitude]). */
    public static double heading(double[] a, double[] b) {
        double h = Math.toDegrees(Math.atan2((b[1] - a[1]) * COS_LAT, b[0] - a[0]));
        return h < 0 ? h + 360 : h;
    }

    /** The angle between two headings, 0-180. */
    public static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** Distance from point p to the segment a-b, all in metres {x, y}. */
    public static double toSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    /** The same with points as {x, y} arrays. */
    public static double toSegment(double[] p, double[] a, double[] b) {
        return toSegment(p[0], p[1], a[0], a[1], b[0], b[1]);
    }

    /**
     * Whether a point is inside a closed ring (even-odd rule). Point and ring in the same coordinates, either
     * {x, y} or [latitude, longitude].
     */
    public static boolean inside(double[] p, double[][] ring) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double[] u = ring[i];
            double[] v = ring[j];
            if ((u[1] > p[1]) != (v[1] > p[1]) && p[0] < (v[0] - u[0]) * (p[1] - u[1]) / (v[1] - u[1]) + u[0]) {
                in = !in;
            }
        }
        return in;
    }
}
