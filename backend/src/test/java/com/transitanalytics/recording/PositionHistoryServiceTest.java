package com.transitanalytics.recording;

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
    void sortsLinesNaturally() {
        assertThat(List.of("N1", "100", "2", "10", "453", "N2").stream().sorted(com.transitanalytics.service.TimetableMath.LINE_ORDER))
                .containsExactly("2", "10", "100", "453", "N1", "N2");
    }

    @Test
    void buildsSortOrderFromAWhitelist() {
        assertThat(PositionHistoryService.SampleSort.orderBy(null)).startsWith("p.observed_at DESC NULLS LAST");
        assertThat(PositionHistoryService.SampleSort.orderBy("delay,desc"))
                .isEqualTo("p.delay_seconds DESC NULLS LAST, p.observed_at DESC, p.vehicle_id");
        assertThat(PositionHistoryService.SampleSort.orderBy("nextStop")).startsWith("p.next_stop_name ASC");
        assertThat(PositionHistoryService.SampleSort.orderBy("line,asc")).startsWith("(p.route_name !~ '^[0-9]+$') ASC");
        assertThatThrownBy(() -> PositionHistoryService.SampleSort.orderBy("latitude; DROP TABLE vehicle"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sort must be one of");
    }

    @Test
    void replayStepsKeepFramesBounded() {
        // 15 min and 1 h at one frame per poll; a day at 5 min; a week at 30 min.
        for (var c : List.of(List.of(15L, 1L), List.of(60L, 1L), List.of(24 * 60L, 5L), List.of(7 * 24 * 60L, 30L))) {
            Duration window = Duration.ofMinutes(c.get(0));
            Duration step = PositionHistoryService.REPLAY_STEPS.stream()
                    .filter(s -> window.dividedBy(s) <= PositionHistoryService.MAX_FRAMES).findFirst().orElseThrow();
            assertThat(step.toMinutes()).as(window.toString()).isEqualTo(c.get(1));
        }
    }
}
