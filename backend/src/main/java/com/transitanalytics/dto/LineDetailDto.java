package com.transitanalytics.dto;

import com.transitanalytics.domain.RouteSubtype;

import java.time.LocalDate;
import java.util.List;

/** One line in detail: its variants and timetable figures, and its performance measured from vehicle positions. */
public final class LineDetailDto {

    private LineDetailDto() {
    }

    /** A line to pick from. {@code line} is the route's GTFS id, used in the detail URLs. */
    public record LineRef(long id, String line, String name, RouteSubtype subtype) {
    }

    /**
     * Timetable figures for the regular weekday service.
     *
     * @param zone2 per direction of the base route; empty when the line stays in zone 1 (the city)
     */
    public record Detail(long id, String line, String name, RouteSubtype subtype, String serviceId, Base base,
                         List<Variant> variants, List<Zone2> zone2, List<Brigade> brigades) {
    }

    /**
     * The base route: the most frequent variant of each direction.
     *
     * @param lengthKm    average of the two directions' lengths
     * @param minSpeedKmh average speed of the slowest scheduled trip (distance / travel time)
     * @param maxSpeedKmh average speed of the fastest scheduled trip
     */
    public record Base(Double lengthKm, Integer shortestMin, Integer longestMin, Double minSpeedKmh,
                       Double maxSpeedKmh) {
    }

    /**
     * One way the line runs: a direction and route shape.
     *
     * @param kind   "main" (the base route of its direction), "longer" or "shorter" than it
     * @param points the route shape as [latitude, longitude] pairs
     */
    public record Variant(int direction, String shapeId, String headsign, String kind, double km, int tripsPerDay,
                          int shortestMin, int longestMin, String firstStop, String lastStop, List<double[]> points,
                          List<StopPoint> stops) {
    }

    /** @param km distance along the route from its first stop */
    public record StopPoint(String name, Double lat, Double lon, double km, String zone) {
    }

    /**
     * The part of a direction's base route outside the city (fare zone 2).
     *
     * @param km distance in zone 2: segments between two zone-2 stops, plus half of each segment crossing the border
     */
    public record Zone2(int direction, String headsign, int stops, int totalStops, double km, double totalKm) {
    }

    /**
     * One bus's duty for the day (brigade). Times are seconds after midnight and can pass 24:00.
     *
     * @param start first departure: the bus leaves the depot and enters service
     * @param end   last arrival: the bus leaves service and returns to the depot
     */
    public record Brigade(String name, int start, int end, List<Run> runs, List<Break> breaks) {
    }

    /** @param kind the variant: "main", "longer" or "shorter" */
    public record Run(int from, int to, int direction, String headsign, String kind) {
    }

    /** A gap between two runs of at least {@code LineDetailService.BREAK_SECONDS}. */
    public record Break(int from, int to) {
    }

    /**
     * Measured from recorded vehicle positions on weekdays (Mon–Fri). Hours are local (Europe/Warsaw) and run past
     * midnight as 24, 25, … so a service day is continuous.
     *
     * @param fullRuns  runs of the base route recorded from the first to the last stop
     * @param durations full-run travel time in minutes, by departure hour
     * @param speeds    full-run average speed in km/h, by departure hour
     * @param delays    average delay per 15 minutes of the day
     * @param segments  speed between consecutive stops in peak hours, per direction
     */
    public record Performance(LocalDate firstDay, LocalDate lastDay, int weekdays, int fullRuns,
                              List<HourStat> durations, List<HourStat> speeds, List<DelaySlot> delays,
                              List<SegmentProfile> segments) {
    }

    public record HourStat(int hour, int runs, double min, double max, double avg, double median) {
    }

    /** @param minute start of the 15-minute slot, minutes after midnight (can pass 1440) */
    public record DelaySlot(int minute, double avgDelaySeconds, long samples) {
    }

    /** @param runs full peak-hour runs the speeds are measured from */
    public record SegmentProfile(int direction, String headsign, int runs, List<Segment> segments) {
    }

    /** @param kmh null when no run passed both stops */
    public record Segment(String from, String to, double fromKm, double toKm, Double kmh) {
    }
}
