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
     * @param route       route filter, or null for all routes
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
     * Delay distribution: on time, 2–5, 5–10, and 10 minutes or more late.
     *
     * @param fromMinutes lower bound, inclusive; null for the first band (under 2 minutes, including early)
     * @param toMinutes   upper bound, exclusive; null for the last band (10 minutes or more)
     * @param vehicles    different vehicles with at least one report in this band (one vehicle can be in several)
     * @param reports     position reports in this band
     */
    public record DelayBand(Integer fromMinutes, Integer toMinutes, long vehicles, long reports) {
    }

    /**
     * A route with recordings in the window, for the route filter and the routes with the most and least delay.
     *
     * @param meanDelaySeconds its reports' mean delay, outliers left out; null when none had a delay
     * @param delaySamples     the reports that mean is over
     */
    public record RouteActivity(String route, long samples, Long meanDelaySeconds, long delaySamples) {
    }

    /**
     * A stop where buses dwell long: the mean of its visits in the window (a bus halting there, measured as a trip's
     * stop is; passing without halting counts 0).
     *
     * @param meanSeconds the visits' mean dwell
     * @param visits      buses' visits to it in the window
     */
    public record StopDwell(String stop, double meanSeconds, long visits) {
    }
}
