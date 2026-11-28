package com.transitanalytics.routes;

import com.transitanalytics.dto.RouteStatsDto;
import com.transitanalytics.routes.RouteStatsService.TripTimes;
import com.transitanalytics.timetable.Periods;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteStatsServiceTest {

    private static int at(int h, int m) {
        return h * 3600 + m * 60;
    }

    /** A trip starting at h:m, lasting the given minutes. */
    private static TripTimes trip(int direction, String shape, String duty, int h, int m, int minutes, Double km) {
        return new TripTimes(1, direction, shape, duty, at(h, m), at(h, m) + minutes * 60, km);
    }

    @Test
    void lengthAndTravelTimesUseEachDirectionsMainVariant() {
        List<TripTimes> trips = List.of(
                trip(0, "A", "1-01", 6, 0, 40, 20.0), trip(0, "A", "1-02", 7, 0, 50, 20.0),
                trip(0, "SHORT", "1-01", 8, 0, 10, 4.0),                       // short turn: ignored
                trip(1, "B", "1-01", 6, 45, 45, 19.0), trip(1, "B", "1-02", 7, 55, 38, 19.0));

        RouteStatsDto.Route route = RouteStatsService.routeStats(null, 1, trips);

        assertThat(route.lengthKm()).isEqualTo(19.5);
        assertThat(route.trips()).isEqualTo(5);
    }

    @Test
    void speedIsTotalDistanceOverTotalTimeOfMainRouteTripsInThePeriod() {
        List<TripTimes> trips = List.of(
                trip(0, "A", "d", 7, 0, 60, 20.0),     // peak: 20 km in 60 min
                trip(0, "A", "d", 8, 0, 30, 20.0),     // peak: 20 km in 30 min → 40 km / 1.5 h = 26.7 km/h
                trip(0, "A", "d", 18, 0, 40, 20.0),    // off-peak: 30 km/h
                trip(0, "SHORT", "d", 7, 30, 2, 5.0),  // short turn, not the main route: ignored
                trip(0, "A", "d", 21, 0, 20, 20.0));   // evening after 20:00: in neither period

        RouteStatsDto.Route route = RouteStatsService.routeStats(null, 1, trips);

        assertThat(route.peakSpeedKmh()).isEqualTo(26.7);
        assertThat(route.offPeakSpeedKmh()).isEqualTo(30.0);
        assertThat(RouteStatsService.speed(List.of(trip(0, "A", "d", 7, 0, 30, null)), Periods.PEAK))
                .as("no distance in the feed").isNull();
    }

    @Test
    void vehiclesAreDutiesRunningInAPeriodWindowTakingTheBusiestWindow() {
        // Peak 06:30–08:30 and 15:00–17:00; off-peak 08:30–15:00 and 17:00–20:00.
        List<TripTimes> trips = List.of(
                trip(0, "A", "1-01", 6, 10, 30, 10.0),   // runs into 06:30 → counts for the morning peak
                trip(0, "A", "1-02", 7, 0, 30, 10.0),
                trip(1, "B", "1-03", 8, 20, 30, 10.0),   // 08:20–08:50: morning peak and late-morning off-peak
                trip(0, "A", "1-01", 15, 30, 30, 10.0),
                trip(1, "B", "1-04", 16, 50, 30, 10.0),  // ends 17:20 → afternoon peak and evening off-peak
                trip(0, "A", "1-05", 19, 59, 30, 10.0),  // starts before 20:00 → evening off-peak
                trip(0, "A", "1-06", 20, 0, 30, 10.0));  // starts at 20:00 → not off-peak

        RouteStatsDto.Route route = RouteStatsService.routeStats(null, 1, trips);

        assertThat(route.peakVehicles()).as("morning 3 duties vs afternoon 2").isEqualTo(3);
    }

    @Test
    void tripsAfterMidnightAndRoutesWithoutShapesWork() {
        List<TripTimes> trips = List.of(trip(0, null, "N1-01", 23, 30, 60, null),
                trip(0, null, "N1-01", 24, 40, 50, null));

        RouteStatsDto.Route route = RouteStatsService.routeStats(null, 7, trips);

        assertThat(route.lengthKm()).isNull();
        assertThat(route.peakIntervalMinutes()).isNull();
        assertThat(route.peakVehicles()).isZero();
        assertThat(route.route()).isEqualTo("7");
    }
}
