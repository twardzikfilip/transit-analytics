package com.transitanalytics.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Until when each imported timetable runs.
 *
 * @param today    today's service day
 * @param warnDays a feed with no more than this many days left is ending
 */
public record TimetableCoverageDto(LocalDate today, int warnDays, List<Feed> feeds) {

    /**
     * @param feed     the feed's key
     * @param lastDay  the last published day with trips; null when nothing is loaded
     * @param daysLeft days after today it still covers: 0 when today is the last, negative once it has ended; null when
     *                 nothing is loaded
     * @param ending   whether it has ended, isn't loaded, or ends within the days to warn
     * @param assumed  days continued with its last known week's services (ahead and past), in order
     */
    public record Feed(String feed, LocalDate lastDay, Integer daysLeft, boolean ending, List<LocalDate> assumed) {
    }
}
