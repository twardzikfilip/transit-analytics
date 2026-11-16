package com.transitanalytics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDayTest {

    @Test
    void serviceHoursAfterMidnightContinueTheDay() {
        // 2026-11-16 23:30 and 2026-11-17 01:30 in Warsaw (UTC+1).
        assertThat(ServiceDay.serviceHour(1_794_868_200L)).isEqualTo(23);
        assertThat(ServiceDay.serviceHour(1_794_875_400L)).isEqualTo(25);
        assertThat(ServiceDay.serviceDay(1_794_875_400L)).isEqualTo(java.time.LocalDate.of(2026, 11, 16));
    }
}
