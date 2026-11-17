package com.transitanalytics.dto;

import java.time.Instant;
import java.util.List;

/** Where buses stand still away from stops (traffic lights, junctions, congestion), from recorded positions. */
public final class HotspotDto {

    private HotspotDto() {
    }

    /**
     * @param line             the line the report is limited to, or null for all
     * @param lines            lines with standstills in the window, to choose from
     * @param days             service days with recorded standstills in the window; per-day figures are over these
     * @param standstills      standstills away from stops and terminals in the window
     * @param standstillHours  their time altogether
     * @param hotspotHours     the part of it in the hotspots (the rest is scattered)
     * @param hotspots         places where buses repeatedly stood still, the most time lost first
     */
    public record Report(String window, Instant from, Instant to, String line, List<String> lines, int days,
                         int standstills, double standstillHours, double hotspotHours, List<Hotspot> hotspots) {
    }

    /**
     * A place where buses repeatedly stood still.
     *
     * @param lat               centre (weighted by time)
     * @param radiusMeters      90% of its standstills are this close to the centre
     * @param hours             time standing still there altogether
     * @param minutesPerDay     the same per recorded day
     * @param standstills       how many times a bus stood still there
     * @param vehicles          different vehicles
     * @param medianSeconds     a typical standstill
     * @param p90Seconds        a long one (90th percentile)
     * @param nearestStop       the nearest stop, to tell where it is
     * @param nearestStopMeters and how far from it
     * @param lines             time per line, the most first
     * @param approaches        the directions buses were heading, by share of the time (10% or more)
     * @param peakHour          the hour of the day (local time) with the most time lost
     */
    public record Hotspot(int id, double lat, double lon, int radiusMeters, double hours, double minutesPerDay,
                          int standstills, int vehicles, int meanSeconds, int medianSeconds, int p90Seconds,
                          String nearestStop, int nearestStopMeters, List<LineTime> lines, List<Approach> approaches,
                          int peakHour) {
    }

    public record LineTime(String line, double hours) {
    }

    /** A compass heading (N, NE, E, …) and its share of the time, 0–1. */
    public record Approach(String heading, double share) {
    }
}
