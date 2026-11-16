package com.transitanalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.transitanalytics.service.Periods.*;

/**
 * The service day: local time in Białystok, running from 03:00 to 03:00, so the hours after midnight (24, 25, 26)
 * belong to the day before.
 */
final class ServiceDay {

    /** A service day runs from 03:00 to 03:00; times before 03:00 count as hours 24, 25 and 26 of the day before. */
    static final int DAY_START_HOUR = 3;
    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    /** Hour of the service day: 3–23, then 24, 25, 26 for the hours after midnight. */
    static int serviceHour(double epochSeconds) {
        int hour = ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE).getHour();
        return hour < DAY_START_HOUR ? hour + 24 : hour;
    }

    static LocalDate serviceDay(double epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE)
                .minusHours(DAY_START_HOUR).toLocalDate();
    }

    /** Whether a run departing then starts in the peak hours of Line statistics. */
    static boolean inPeak(double epochSeconds) {
        int second = ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE).toLocalTime()
                .toSecondOfDay();
        return Periods.PEAK.stream().anyMatch(w -> second >= w.from() && second < w.to());
    }
}
