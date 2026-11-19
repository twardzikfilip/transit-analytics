package com.transitanalytics.recording;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** Daily recording hours, e.g. 03:30–01:30 (crossing midnight). Both ends are inclusive. */
public record RecordingWindow(LocalTime start, LocalTime end, ZoneId zone) {

    public boolean contains(Instant instant) {
        LocalTime time = instant.atZone(zone).toLocalTime();
        if (!start.isAfter(end)) {
            return !time.isBefore(start) && !time.isAfter(end);
        }
        // Spans midnight: from start until 24:00, and from 00:00 until end.
        return !time.isBefore(start) || !time.isAfter(end);
    }
}
