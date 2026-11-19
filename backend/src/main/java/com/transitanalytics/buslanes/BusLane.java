package com.transitanalytics.buslanes;

import java.util.List;

/**
 * A street section (an OpenStreetMap way) with a lane for buses.
 *
 * @param id       the way's id
 * @param name     the street's name; null when the way has none
 * @param forward  bus lanes in the way's direction (the order of {@code points})
 * @param backward bus lanes against it
 * @param points   [latitude, longitude] in order
 */
public record BusLane(long id, String name, String highway, int forward, int backward, List<double[]> points) {

    public BusLane {
        points = List.copyOf(points);
    }

    /** Length of the section in metres. */
    public double meters() {
        double m = 0;
        for (int i = 1; i < points.size(); i++) {
            m += Geo.meters(points.get(i - 1), points.get(i));
        }
        return m;
    }
}
