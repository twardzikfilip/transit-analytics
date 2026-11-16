package com.transitanalytics.dto;

import java.time.LocalDate;
import java.util.List;

/** Per-line statistics from the weekday timetable (GTFS). */
public final class LineStatsDto {

    private LineStatsDto() {
    }

    /**
     * @param serviceId the weekday service used, e.g. P_1349; null (and no lines) before a timetable is imported
     * @param validDays weekdays this service runs on
     * @param firstDate first of those days
     * @param lastDate  last of those days
     */
    public record Report(String serviceId, int validDays, LocalDate firstDate, LocalDate lastDate,
                         List<Period> peak, List<Period> offPeak, List<Line> lines) {
    }

    /** A time period of the day, e.g. 06:30–08:30. */
    public record Period(String from, String to) {
    }

    /**
     * @param lengthKm               main route length (main variant of each direction, averaged over directions)
     * @param shortestTravelSeconds  shortest end-to-end trip on the main route
     * @param longestTravelSeconds   longest end-to-end trip on the main route
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
     * @param offPeakVehicles        duties running in the off-peak period
     * @param trips                  weekday trips of the line (all variants)
     */
    public record Line(Long routeId, String route, String color, String textColor, Double lengthKm,
                       Integer shortestTravelSeconds, Integer longestTravelSeconds, Double peakSpeedKmh,
                       Double offPeakSpeedKmh, Double peakIntervalMinutes,
                       Double offPeakIntervalMinutes, Double peakRegularityCv, Double offPeakRegularityCv,
                       int peakVehicles, int offPeakVehicles, int trips) {
    }
}
