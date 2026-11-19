package com.transitanalytics.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CityBoundariesTest {

    @Test
    void ringsAreClosedPolygons() {
        for (double[][] ring : new double[][][] {CityBoundaries.CITY, CityBoundaries.CENTRE}) {
            assertThat(ring.length).isGreaterThan(3);
            assertThat(ring[0]).containsExactly(ring[ring.length - 1]);
            for (double[] point : ring) {
                assertThat(point).hasSize(2);
            }
        }
    }

    @Test
    void centreLiesInsideTheCity() {
        for (double[] point : CityBoundaries.CENTRE) {
            assertThat(inside(CityBoundaries.CITY, point[0], point[1])).isTrue();
        }
        // Rynek Kościuszki is in the centre; Supraśl and Wasilków are outside the city.
        assertThat(inside(CityBoundaries.CENTRE, 53.1322, 23.1590)).isTrue();
        assertThat(inside(CityBoundaries.CITY, 53.2054, 23.3089)).isFalse();
        assertThat(inside(CityBoundaries.CITY, 53.1990, 23.2060)).isFalse();
    }

    private static boolean inside(double[][] ring, double lat, double lon) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double latI = ring[i][0], lonI = ring[i][1], latJ = ring[j][0], lonJ = ring[j][1];
            if ((latI > lat) != (latJ > lat)
                    && lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) {
                in = !in;
            }
        }
        return in;
    }
}
