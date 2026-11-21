package com.transitanalytics.service;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A grid of square cells over a flat map in metres (see {@link com.transitanalytics.geo.Geo#xy}), for measuring areas
 * that overlap: a cell belongs to a shape when its centre is inside it. With 10 m cells, a district's area comes out
 * within a fraction of a percent.
 */
final class AreaGrid {

    final double minX;
    final double minY;
    final double cell;
    final int nx;
    final int ny;

    AreaGrid(double minX, double minY, double maxX, double maxY, double cell) {
        this.minX = minX;
        this.minY = minY;
        this.cell = cell;
        this.nx = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
        this.ny = Math.max(1, (int) Math.ceil((maxY - minY) / cell));
    }

    int size() {
        return nx * ny;
    }

    /** km² of so many cells. */
    double km2(long cells) {
        return cells * cell * cell / 1e6;
    }

    /**
     * Calls {@code action} with each cell whose centre is inside the shape: the rings (outer ones and holes alike,
     * each a list of {x, y}) taken even-odd, so a hole's cells are left out.
     */
    void fill(List<double[][]> rings, IntConsumer action) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        int edges = 0;
        for (double[][] ring : rings) {
            for (double[] p : ring) {
                lo = Math.min(lo, p[1]);
                hi = Math.max(hi, p[1]);
            }
            edges += ring.length;
        }
        int j0 = Math.max(0, (int) Math.floor((lo - minY) / cell - 0.5));
        int j1 = Math.min(ny - 1, (int) Math.ceil((hi - minY) / cell - 0.5));
        double[] xs = new double[edges];
        for (int j = j0; j <= j1; j++) {
            double y = minY + (j + 0.5) * cell;
            int n = 0;
            for (double[][] ring : rings) {
                for (int k = 0; k < ring.length; k++) {
                    double[] a = ring[k];
                    double[] b = ring[(k + 1) % ring.length];
                    if ((a[1] <= y) != (b[1] <= y)) {
                        xs[n++] = a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
                    }
                }
            }
            Arrays.sort(xs, 0, n);
            for (int k = 0; k + 1 < n; k += 2) {
                int i0 = Math.max(0, (int) Math.ceil((xs[k] - minX) / cell - 0.5));
                int i1 = Math.min(nx - 1, (int) Math.ceil((xs[k + 1] - minX) / cell - 0.5) - 1);
                for (int i = i0; i <= i1; i++) {
                    action.accept(j * nx + i);
                }
            }
        }
    }

    /** Calls {@code action} with each cell whose centre is within {@code radius} of (x, y). */
    void disc(double x, double y, double radius, IntConsumer action) {
        int j0 = Math.max(0, (int) Math.ceil((y - radius - minY) / cell - 0.5));
        int j1 = Math.min(ny - 1, (int) Math.floor((y + radius - minY) / cell - 0.5));
        for (int j = j0; j <= j1; j++) {
            double dy = minY + (j + 0.5) * cell - y;
            double half = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int i0 = Math.max(0, (int) Math.ceil((x - half - minX) / cell - 0.5));
            int i1 = Math.min(nx - 1, (int) Math.floor((x + half - minX) / cell - 0.5));
            for (int i = i0; i <= i1; i++) {
                action.accept(j * nx + i);
            }
        }
    }
}
