package com.transitanalytics.dto;

import java.util.List;

/** Important corridors: stretches of consecutive stops that several lines share, from the weekday timetable. */
public final class CorridorDto {

    private CorridorDto() {
    }

    /**
     * @param peak      peak periods the intervals and regularity are measured in
     * @param offPeak   off-peak periods
     * @param corridors busiest first (most buses a weekday)
     * @param segments  every stretch between two consecutive stops, for the maps (quietest first, so busy ones are
     *                  drawn on top)
     */
    public record Report(String serviceId, List<LineStatsDto.Period> peak, List<LineStatsDto.Period> offPeak,
                         List<Corridor> corridors, List<SegmentLoad> segments) {
    }

    /**
     * Buses between two consecutive stops on a weekday, both directions together.
     *
     * @param peakBuses buses leaving the first stop in the peak periods
     * @param buses     buses all day
     * @param points    the route between the stops as [latitude, longitude] pairs
     */
    public record SegmentLoad(String from, String to, int peakBuses, int buses, List<double[]> points) {
    }

    /**
     * A stretch of at least four stops shared by two or more lines on each part, in both directions where the lines
     * run back the same way. Stops, start and end are those of the busier direction. Buses, interval and regularity
     * are measured on each stretch between two stops (all buses using it) and averaged along the corridor, weighted
     * by length.
     *
     * @param lines              every line running on the corridor (in line order)
     * @param throughLines       the lines running all of it; the others run part of it
     * @param km                 length, averaged over the directions
     * @param buses              weekday buses on the corridor's stretches (averaged along it), both directions
     * @param oneWay             true when the lines don't run it back the same way
     * @param peakIntervalMin    average minutes between buses in peak hours, averaged along it and over directions
     * @param offPeakIntervalMin the same off-peak
     * @param peakCv             regularity: coefficient of variation of the gaps between buses (0 = perfectly even)
     * @param offPeakCv          the same off-peak
     */
    public record Corridor(String from, String to, List<String> stops, List<String> lines, List<String> throughLines,
                           Double km, int buses,
                           boolean oneWay, Double peakIntervalMin, Double offPeakIntervalMin, Double peakCv,
                           Double offPeakCv) {
    }
}
