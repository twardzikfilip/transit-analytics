package com.transitanalytics.data;

import com.transitanalytics.recording.DelayOutliers;

import java.time.Instant;

/** A recorded position: who, when, on which route and brigade, its delay and the next stop's sequence number. */
public record Report(long vehicleId, String vehicle, Instant time, String route, String brigade, String headsign,
                     Integer delay, Integer nextSeq, double lat, double lon) {

    /** The scheduled time the bus was running to: the report's time minus its delay (outliers ignored). */
    public Instant scheduled() {
        return delay == null || Math.abs(delay) > DelayOutliers.THRESHOLD_SECONDS ? time
                : time.minusSeconds(delay);
    }

    /** The delay, unless it is an outlier (a feed error). */
    public Integer usableDelay() {
        return delay == null || Math.abs(delay) > DelayOutliers.THRESHOLD_SECONDS ? null : delay;
    }
}
