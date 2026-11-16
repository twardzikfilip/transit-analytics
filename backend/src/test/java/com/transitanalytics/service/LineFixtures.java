package com.transitanalytics.service;

import com.transitanalytics.dto.LineDetailDto.StopPoint;

/** Stops for the line timetable and run tracking unit tests. */
final class LineFixtures {

    private LineFixtures() {
    }

    /** A stop named after its distance ("S2.5"), at a fixed position, in the given fare zone. */
    static StopPoint stop(double km, String zone) {
        return new StopPoint("S" + km, 53.0, 23.0, km, zone);
    }
}
