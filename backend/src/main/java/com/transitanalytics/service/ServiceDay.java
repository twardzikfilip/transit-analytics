package com.transitanalytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    /**
     * The brigade (vehicle duty) of a trip, in SQL on {@code trip t}: its block id, else the trip id's prefix, as in
     * this feed (103-05_P_1349_… is brigade 103-05). Live feeds name brigades the same way.
     */
    static final String BRIGADE_SQL = "coalesce(t.block_id, split_part(t.gtfs_id, '_', 1))";

    /** GTFS times before this (03:00, in seconds) belong to the service day before the timetable's date. */
    static final int DAY_START_SECONDS = DAY_START_HOUR * 3600;

    private static final String RUNS_ON =
            "t.service_id IN (SELECT service_id FROM calendar_date WHERE date = ? AND exception_type = 1)";
    private static final String STARTS_BEFORE_DAY_START = "EXISTS (SELECT 1 FROM stop_time e WHERE e.trip_id = t.id"
            + " AND e.departure_time::interval < interval '" + DAY_START_HOUR + " hours')";

    /**
     * The trips of a service day, in SQL on {@code trip t}; takes the parameters {@link #tripsOfDayArgs}. A timetable
     * gives trips after midnight either as 24:10 on the day, or as 00:10 on the next date (as this one does for the
     * night lines); both belong to the service day that started the evening before, as the hours after midnight do.
     */
    static final String TRIPS_OF_DAY_SQL = "(" + RUNS_ON + " AND NOT " + STARTS_BEFORE_DAY_START
            + " OR " + RUNS_ON + " AND " + STARTS_BEFORE_DAY_START + ")";

    /** The parameters of {@link #TRIPS_OF_DAY_SQL}: the day and the date after it. */
    static Object[] tripsOfDayArgs(LocalDate day) {
        return new Object[] {day, day.plusDays(1)};
    }

    /**
     * What a trip of the service day counts its GTFS times from, given its first departure: trips starting before
     * 03:00 are on the next date's timetable (see {@link #TRIPS_OF_DAY_SQL}).
     */
    static Instant tripTimeZero(LocalDate day, int firstDeparture) {
        return gtfsTimeZero(firstDeparture < DAY_START_SECONDS ? day.plusDays(1) : day);
    }

    static LocalDate serviceDay(double epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE)
                .minusHours(DAY_START_HOUR).toLocalDate();
    }

    static LocalDate serviceDay(Instant instant) {
        return serviceDay(instant.getEpochSecond());
    }

    /** When a service day begins: 03:00 local time. */
    static Instant start(LocalDate day) {
        return day.atTime(DAY_START_HOUR, 0).atZone(ZONE).toInstant();
    }

    /**
     * What GTFS times on that day count from (e.g. 25:10:00 is 25 h 10 min after it): noon minus 12 hours, which is
     * midnight except on days the clocks change.
     */
    static Instant gtfsTimeZero(LocalDate day) {
        return day.atTime(LocalTime.NOON).atZone(ZONE).minusHours(12).toInstant();
    }

    private static final java.time.format.DateTimeFormatter HH_MM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);

    /** Local time of day, "07:41". */
    static String localTime(Instant instant) {
        return HH_MM.format(instant);
    }

    /** Whether a run departing then starts in the peak hours of Line statistics. */
    static boolean inPeak(double epochSeconds) {
        int second = ZonedDateTime.ofInstant(Instant.ofEpochSecond((long) epochSeconds), ZONE).toLocalTime()
                .toSecondOfDay();
        return Periods.PEAK.stream().anyMatch(w -> second >= w.from() && second < w.to());
    }
}
