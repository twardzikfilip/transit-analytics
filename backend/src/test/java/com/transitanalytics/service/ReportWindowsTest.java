package com.transitanalytics.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportWindowsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-11-01T09:30:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReportWindows windows = new ReportWindows(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fillsInNowAndTheFirstRecording() {
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.from(FIRST));
        Instant from = Instant.parse("2026-11-17T11:00:00Z");

        assertThat(windows.resolve(from, null)).isEqualTo(new ReportWindow(from, NOW));
        assertThat(windows.resolve(null, null)).as("all time").isEqualTo(new ReportWindow(FIRST, NOW));
        assertThat(windows.resolve(null, FIRST.minusSeconds(60)))
                .as("ending before the first recording: the day before")
                .isEqualTo(new ReportWindow(FIRST.minusSeconds(60).minus(Duration.ofDays(1)), FIRST.minusSeconds(60)));
        assertThat(windows.last(Duration.ofHours(1))).isEqualTo(new ReportWindow(NOW.minusSeconds(3600), NOW));
        windows.resolve(null, null);
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Timestamp.class));
    }

    @Test
    void withNothingRecordedAllTimeIsTheLastDay() {
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(null);
        assertThat(windows.resolve(null, null)).isEqualTo(new ReportWindow(NOW.minus(Duration.ofDays(1)), NOW));
    }

    @Test
    void rejectsAnEmptyOrBackwardWindow() {
        assertThatThrownBy(() -> windows.resolve(NOW, NOW)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be before to");
        assertThatThrownBy(() -> new ReportWindow(NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReportWindow(FIRST, NOW).length()).isEqualTo(Duration.between(FIRST, NOW));
    }
}
