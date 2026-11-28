package com.transitanalytics.routes;

import com.transitanalytics.dto.RouteDetailDto.StopPoint;

/** Stops for the route timetable and run tracking unit tests. */
final class RouteFixtures {

    private RouteFixtures() {
    }

    /** A stop named after its distance ("S2.5"), at a fixed position, in the given fare zone. */
    public static StopPoint stop(double km, String zone) {
        return new StopPoint("S" + km, 53.0, 23.0, km, zone);
    }
}
