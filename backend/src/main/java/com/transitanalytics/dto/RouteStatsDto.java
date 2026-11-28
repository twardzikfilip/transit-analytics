package com.transitanalytics.dto;

import java.util.List;

/** Per-line statistics from the weekday timetable (GTFS). */
public final class RouteStatsDto {

    private RouteStatsDto() {
    }

    /**
     * @param serviceId the weekday service used, e.g. P_1349; null (and no routes) before a timetable is imported
     * @param delayDays the recorded weekdays the routes' delays are averaged over: the last this many days
     */
    public record Report(String serviceId, List<Period> peak, List<Period> offPeak, int delayDays, List<Route> routes) {
    }

    /** A time period of the day, e.g. 06:30–08:30. */
    public record Period(String from, String to) {
    }

    /**
     * @param lengthKm               main route length (main variant of each direction, averaged over directions)
     * @param peakSpeedKmh           average speed of main-route trips starting in peak hours (total distance / total
     *                               time); null without such trips or without distances in the feed
     * @param offPeakSpeedKmh        the same for off-peak hours
     * @param peakIntervalMinutes    average time between departures in peak hours, per direction; null without trips
     * @param offPeakIntervalMinutes the same for off-peak hours
     * @param peakRegularityCv       how evenly departures are spread in peak hours: coefficient of variation of the
     *                               gaps between departures (standard deviation / mean) at the main route's first
     *                               stop; 0 = perfectly even. Null with too few departures
     * @param offPeakRegularityCv    the same for off-peak hours
     * @param peakVehicles           buses needed at peak: duties running in the busier of the two peak periods
     * @param trips                  weekday trips of the route (all variants)
     * @param peakDelaySeconds       recorded: the route's buses' mean delay in peak hours on weekdays of the last
     *                               {@link Report#delayDays()} days (negative: early); null without recordings
     * @param offPeakDelaySeconds    the same for off-peak hours
     */
    public record Route(Long routeId, String route, String color, String textColor, Double lengthKm,
                       Double peakSpeedKmh, Double offPeakSpeedKmh, Double peakIntervalMinutes,
                       Double offPeakIntervalMinutes, Double peakRegularityCv, Double offPeakRegularityCv,
                       int peakVehicles, int trips, Double peakDelaySeconds, Double offPeakDelaySeconds) {

        /** This route with its recorded delays. */
        public Route withDelays(Double peak, Double offPeak) {
            return new Route(routeId, route, color, textColor, lengthKm, peakSpeedKmh, offPeakSpeedKmh,
                    peakIntervalMinutes, offPeakIntervalMinutes, peakRegularityCv, offPeakRegularityCv, peakVehicles,
                    trips, peak, offPeak);
        }
    }
}
