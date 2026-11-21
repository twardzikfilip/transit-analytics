package com.transitanalytics.service;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AreaGridTest {

    private final AreaGrid grid = new AreaGrid(0, 0, 2000, 2000, 10);

    @Test
    void measuresASquareWithAHoleAndATriangle() {
        double[][] square = {{100, 100}, {1100, 100}, {1100, 1100}, {100, 1100}};
        double[][] hole = {{400, 400}, {600, 400}, {600, 600}, {400, 600}};
        BitSet cells = new BitSet();
        grid.fill(List.of(square, hole), cells::set);
        assertThat(grid.km2(cells.cardinality())).isEqualTo(1.0 - 0.04, within(1e-9));

        BitSet triangle = new BitSet();
        grid.fill(List.<double[][]>of(new double[][] {{0, 0}, {1000, 0}, {0, 1000}}), triangle::set);
        assertThat(grid.km2(triangle.cardinality())).isCloseTo(0.5, within(0.01));
    }

    @Test
    void aDiscIsAboutPiRSquaredAndStaysOnTheGrid() {
        BitSet cells = new BitSet();
        grid.disc(1000, 1000, 400, cells::set);
        assertThat(grid.km2(cells.cardinality())).isCloseTo(Math.PI * 0.4 * 0.4, within(0.005));

        BitSet corner = new BitSet();
        grid.disc(0, 0, 400, corner::set);
        assertThat(grid.km2(corner.cardinality())).isCloseTo(Math.PI * 0.4 * 0.4 / 4, within(0.005));
    }
}
