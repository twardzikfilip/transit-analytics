package com.transitanalytics.recording;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingWindowTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private final RecordingWindow window = new RecordingWindow(LocalTime.of(3, 30), LocalTime.of(1, 30), WARSAW);

    private static Instant warsaw(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(WARSAW).toInstant();
    }

    @Test
    void spansMidnightWithBothEndsIncluded() {
        assertThat(window.contains(warsaw("2026-11-16T03:29:59"))).isFalse();
        assertThat(window.contains(warsaw("2026-11-16T03:30:00"))).isTrue();
        assertThat(window.contains(warsaw("2026-11-16T12:00:00"))).isTrue();
        assertThat(window.contains(warsaw("2026-11-16T23:59:00"))).isTrue();
        assertThat(window.contains(warsaw("2026-11-17T00:30:00"))).isTrue();
        assertThat(window.contains(warsaw("2026-11-17T01:30:00"))).isTrue();
        assertThat(window.contains(warsaw("2026-11-17T01:31:00"))).isFalse();
        assertThat(window.contains(warsaw("2026-11-17T02:45:00"))).isFalse();
    }

    @Test
    void usesWarsawTimeAcrossDaylightSavingChanges() {
        // Summer time (UTC+2): 01:00 UTC is 03:00 in Warsaw, outside; 01:30 UTC is 03:30, inside.
        assertThat(window.contains(Instant.parse("2026-07-01T01:00:00Z"))).isFalse();
        assertThat(window.contains(Instant.parse("2026-07-01T01:30:00Z"))).isTrue();
        // Winter time (UTC+1): 02:30 UTC is 03:30 in Warsaw, inside; 00:45 UTC is 01:45, outside.
        assertThat(window.contains(Instant.parse("2026-12-01T02:30:00Z"))).isTrue();
        assertThat(window.contains(Instant.parse("2026-12-01T00:45:00Z"))).isFalse();
        // The night clocks go back (25 Oct 2026, 03:00 → 02:00): the repeated hour is outside the window.
        assertThat(window.contains(Instant.parse("2026-10-25T00:30:00Z"))).isFalse(); // 02:30 CEST
        assertThat(window.contains(Instant.parse("2026-10-25T01:30:00Z"))).isFalse(); // 02:30 CET
        assertThat(window.contains(Instant.parse("2026-10-25T02:30:00Z"))).isTrue();  // 03:30 CET
    }

    @Test
    void supportsWindowsWithinOneDay() {
        RecordingWindow day = new RecordingWindow(LocalTime.of(6, 0), LocalTime.of(22, 0), WARSAW);
        assertThat(day.contains(warsaw("2026-11-16T05:59:00"))).isFalse();
        assertThat(day.contains(warsaw("2026-11-16T06:00:00"))).isTrue();
        assertThat(day.contains(warsaw("2026-11-16T22:00:00"))).isTrue();
        assertThat(day.contains(warsaw("2026-11-16T23:00:00"))).isFalse();
    }
}
