package com.transitanalytics.recording;

import com.transitanalytics.timetable.TimetableMath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionHistoryServiceTest {

    private static final Instant T = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void picksEachPollUpToAnHourOneMinuteUpToADayAndCoarserStepsBeyond() {
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofMinutes(1)))).isEqualTo(Duration.ofSeconds(10));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofHours(1)))).isEqualTo(Duration.ofSeconds(10));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofHours(3)))).isEqualTo(Duration.ofMinutes(1));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofHours(24)))).isEqualTo(Duration.ofMinutes(1));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofDays(7)))).isEqualTo(Duration.ofMinutes(10));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofDays(31)))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsMissingReversedOrTooLongWindows() {
        assertThatThrownBy(() -> PositionHistoryService.validate(null, T)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PositionHistoryService.validate(T, T)).hasMessageContaining("before");
        assertThatThrownBy(() -> PositionHistoryService.validate(T, T.plus(Duration.ofDays(367))))
                .hasMessageContaining("366 days");
        PositionHistoryService.validate(T, T.plus(Duration.ofDays(366)));
        assertThat(PositionHistoryService.stepFor(T, T.plus(Duration.ofDays(366)))).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void sortsRoutesNaturally() {
        assertThat(List.of("N1", "100", "2", "10", "453", "N2").stream().sorted(com.transitanalytics.timetable.TimetableMath.ROUTE_ORDER))
                .containsExactly("2", "10", "100", "453", "N1", "N2");
    }

}
