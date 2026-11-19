package com.transitanalytics.buslanes;

/** Distances and headings across a city: a flat projection around Białystok's latitude, metres east and north. */
public final class Geo {

    static final double M_PER_DEG = 111_320;
    static final double COS_LAT = Math.cos(Math.toRadians(53.13));

    private Geo() {
    }

    /** [latitude, longitude] to metres {east, north}. */
    public static double[] xy(double lat, double lon) {
        return new double[] {lon * M_PER_DEG * COS_LAT, lat * M_PER_DEG};
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
}
