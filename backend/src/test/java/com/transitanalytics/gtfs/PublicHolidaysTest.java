package com.transitanalytics.gtfs;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHolidaysTest {

    @Test
    void findsEaster() {
        assertThat(PublicHolidays.easter(2024)).isEqualTo("2024-03-31");
        assertThat(PublicHolidays.easter(2025)).isEqualTo("2025-04-20");
        assertThat(PublicHolidays.easter(2026)).isEqualTo("2026-04-05");
        assertThat(PublicHolidays.easter(2027)).isEqualTo("2027-03-28");
        assertThat(PublicHolidays.easter(2038)).isEqualTo("2038-04-25");
    }

    @Test
    void knowsTheFixedAndTheMovingHolidays() {
        for (String day : new String[] {"2026-12-24", "2026-12-25", "2026-12-26", "2027-01-01", "2027-01-06",
                "2027-03-29", "2027-05-27", "2026-11-11"}) {
            assertThat(PublicHolidays.isHoliday(LocalDate.parse(day))).as(day).isTrue();
        }
        for (String day : new String[] {"2026-12-23", "2026-12-27", "2026-12-31", "2027-05-28"}) {
            assertThat(PublicHolidays.isHoliday(LocalDate.parse(day))).as(day).isFalse();
        }
    }
}
