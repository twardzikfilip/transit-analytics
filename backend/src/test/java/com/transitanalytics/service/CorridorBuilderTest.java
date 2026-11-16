package com.transitanalytics.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pieces of corridor building: where a trip runs a stretch, and how two stretches overlap end to start. */
class CorridorBuilderTest {

    @Test
    void overlapIsTheSharedEndAndStart() {
        assertThat(CorridorBuilder.overlap(List.of(1L, 2L, 3L, 4L), List.of(3L, 4L, 5L, 6L))).isEqualTo(2);
        assertThat(CorridorBuilder.overlap(List.of(1L, 2L, 3L, 4L), List.of(4L, 5L, 6L))).isEqualTo(1);
        // Inside, apart, or making a loop: no join.
        assertThat(CorridorBuilder.overlap(List.of(1L, 2L, 3L, 4L), List.of(2L, 3L, 4L))).isZero();
        assertThat(CorridorBuilder.overlap(List.of(1L, 2L, 3L, 4L), List.of(7L, 8L, 9L))).isZero();
        assertThat(CorridorBuilder.overlap(List.of(1L, 2L, 3L, 4L), List.of(4L, 5L, 1L))).isZero();
    }

    @Test
    void findsWhereATripRunsAStretch() {
        assertThat(CorridorBuilder.runsThrough(new long[]{9, 1, 2, 3, 8}, List.of(1L, 2L, 3L))).isEqualTo(1);
        assertThat(CorridorBuilder.runsThrough(new long[]{1, 2, 9, 3}, List.of(1L, 2L, 3L))).isEqualTo(-1);
        assertThat(CorridorBuilder.runsThrough(new long[]{1, 2}, List.of(1L, 2L, 3L))).isEqualTo(-1);
    }
}
