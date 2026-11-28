package com.transitanalytics.timetable;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDayTest {

    @Test
    void serviceHoursAfterMidnightContinueTheDay() {
        // 2026-11-16 23:30 and 2026-11-17 01:30 in Warsaw (UTC+1).
        assertThat(ServiceDay.serviceHour(1_794_868_200L)).isEqualTo(23);
        assertThat(ServiceDay.serviceHour(1_794_875_400L)).isEqualTo(25);
        assertThat(ServiceDay.serviceDay(1_794_875_400L)).isEqualTo(java.time.LocalDate.of(2026, 11, 16));
    }

    @Test
    void serviceDaysStartAt3AmAndGtfsTimesCountFromMidnight() {
        LocalDate day = LocalDate.of(2026, 11, 16);
        assertThat(ServiceDay.start(day)).isEqualTo(Instant.parse("2026-11-16T02:00:00Z"));
        assertThat(ServiceDay.serviceDay(Instant.parse("2026-11-16T01:30:00Z"))).isEqualTo(day.minusDays(1));
        assertThat(ServiceDay.serviceDay(Instant.parse("2026-11-16T02:00:00Z"))).isEqualTo(day);
        assertThat(ServiceDay.gtfsTimeZero(day)).isEqualTo(Instant.parse("2026-11-15T23:00:00Z"));
        // The clocks go back on 25 October 2026: GTFS times count from 01:00 local time (noon minus 12 hours).
        assertThat(ServiceDay.gtfsTimeZero(LocalDate.of(2026, 10, 25))).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"));
    }

    @Test
    void tripsStartingBeforeThreeCountFromTheNextDatesTimeZero() {
        LocalDate day = LocalDate.of(2026, 11, 20);
        // N1 at 00:10 on Saturday's timetable runs the night after Friday.
        assertThat(ServiceDay.tripTimeZero(day, 10 * 60)).isEqualTo(ServiceDay.gtfsTimeZero(day.plusDays(1)));
        assertThat(ServiceDay.tripTimeZero(day, 3 * 3600)).isEqualTo(ServiceDay.gtfsTimeZero(day));
        // 24:10 on Friday's timetable is the same night.
        assertThat(ServiceDay.tripTimeZero(day, 24 * 3600 + 10 * 60)).isEqualTo(ServiceDay.gtfsTimeZero(day));
    }
}
