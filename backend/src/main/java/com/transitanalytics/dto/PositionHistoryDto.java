package com.transitanalytics.dto;

import java.time.Instant;
import java.util.List;

/** Recorded vehicle positions (time series) for the frontend. */
public final class PositionHistoryDto {

    private PositionHistoryDto() {
    }

    /**
     * Vehicles and delay per time bucket. Buckets are made of polls (one per minute), so a 1-minute bucket is exactly
     * one poll of the feed.
     *
     * @param stepSeconds bucket size, chosen from the window length (1 minute for windows up to 24 hours)
     * @param route       line filter, or null for all lines
     * @param points      only buckets with data; a missing bucket means nothing was recorded then
     */
    public record FleetSeries(Instant from, Instant to, long stepSeconds, String route, List<FleetPoint> points,
                              Summary summary) {
    }

    /**
     * @param vehicles         vehicles running, averaged over the polls in the bucket; a vehicle is running at a poll
     *                         when it reported in the minute up to it
     * @param peakVehicles     the most vehicles running at one poll in the bucket
     * @param samples          position reports in the bucket
     * @param meanDelaySeconds mean delay of all reports in the bucket (null when none had a delay)
     */
    public record FleetPoint(Instant time, double vehicles, long peakVehicles, long samples, Double meanDelaySeconds) {
    }

    /**
     * Totals over the whole window.
     *
     * @param vehicles       different vehicles seen at any time in the window. Larger than the number running at
     *                       once: vehicles go off duty and others start during the window.
     * @param peakVehicles   the most vehicles running at once (at one poll)
     * @param latestVehicles vehicles running at the latest poll of the window
     */
    public record Summary(long samples, long vehicles, long peakVehicles, long latestVehicles, Instant latestPoll,
                          Double meanDelaySeconds, Instant first, Instant last) {
    }

    /**
     * Delay distribution: one band per minute of delay.
     *
     * @param fromMinutes lower bound, inclusive; null for the first band (under 1 minute, including early)
     * @param toMinutes   upper bound, exclusive; null for the last band (10 minutes or more)
     * @param vehicles    different vehicles with at least one report in this band (one vehicle can be in several)
     * @param reports     position reports in this band
     */
    public record DelayBand(Integer fromMinutes, Integer toMinutes, long vehicles, long reports) {
    }

    /** A line with recordings in the window, for the line filter. */
    public record RouteActivity(String route, long samples) {
    }

    /**
     * Recorded positions as animation frames.
     *
     * @param stepSeconds time between frames (one poll = 60 s for short windows, coarser for long ones)
     * @param routes      the line filter, or empty for all lines
     * @param vehicles    vehicle numbers; frame positions refer to them by index
     * @param lines       line names; frame positions refer to them by index
     * @param brigades    brigades (vehicle duties, e.g. 024-02); frame positions refer to them by index
     * @param frames      only frames with data, in time order
     */
    public record Replay(Instant from, Instant to, long stepSeconds, List<String> routes, List<String> vehicles,
                         List<String> lines, List<String> brigades, List<Frame> frames) {
    }

    /**
     * The latest position of every vehicle in one step.
     *
     * @param positions compact rows {@code [vehicleIndex, latitude, longitude, delaySeconds or null,
     *                  lineIndex or null, brigadeIndex or null]} to keep a day of frames small
     */
    public record Frame(Instant time, List<List<Object>> positions) {
    }

    /** One recorded position report. */
    public record Sample(Instant observedAt, Long vehicleId, String vehicleNumber, String routeName, String brigade,
                         String headsign, double latitude, double longitude, Integer bearing, Integer delaySeconds,
                         String nextStopName) {
    }
}
