package com.transitanalytics.recorder;

import com.transitanalytics.recording.RecordingWindow;
import com.transitanalytics.zbiorkom.LiveSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class LiveAndHealthTest {

    private final PositionRecorder recorder = mock(PositionRecorder.class);
    private final Spool spool = mock(Spool.class);
    private final MutableClock clock = new MutableClock(PositionRecorderTest.NOON);

    private RecordingHealth health(boolean enabled) {
        RecordingProperties p = PositionRecorderTest.properties(enabled, null);
        return new RecordingHealth(recorder, spool, new RecordingWindow(p.windowStart(), p.windowEnd(), p.zone()), p,
                clock);
    }

    @Test
    void servesTheLatestSnapshot() {
        LiveSnapshot snapshot = new LiveSnapshot(PositionRecorderTest.NOON,
                List.of(PositionRecorderTest.position(1L, "230")));
        given(recorder.live()).willReturn(snapshot);
        assertThat(new LiveController(recorder).positions()).isSameAs(snapshot);
        assertThat(new LiveSnapshot(PositionRecorderTest.NOON, null).positions()).isEmpty();
    }

    @Test
    void isHealthyWhilePollsAreStoredOrSpooled() {
        RecordingHealth health = health(true);
        // Just started: nothing yet, but a minute's grace.
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        clock.advance(Duration.ofSeconds(90));
        Instant now = clock.instant();
        assertThat(health.health().getStatus()).as("no poll").isEqualTo(Status.DOWN);

        given(recorder.lastPoll()).willReturn(now.minusSeconds(5));
        assertThat(health.health().getDetails()).containsEntry("reason", "nothing stored in the last minute");
        given(spool.size()).willReturn(3);
        assertThat(health.health().getDetails()).as("can't be kept without the archive")
                .containsEntry("reason", "nothing archived in the last minute");
        given(recorder.lastArchived()).willReturn(now.minusSeconds(5));
        assertThat(health.health().getStatus()).as("kept in the spool").isEqualTo(Status.UP);
        given(spool.size()).willReturn(0);
        given(recorder.lastStored()).willReturn(now.minusSeconds(5));
        Health up = health.health();
        assertThat(up.getStatus()).isEqualTo(Status.UP);
        assertThat(up.getDetails()).containsKeys("lastPoll", "lastStored", "lastArchived", "spooled");

        given(recorder.lastArchived()).willReturn(now.minusSeconds(90));
        assertThat(health.health().getStatus()).as("stored, but not archived").isEqualTo(Status.DOWN);
    }

    @Test
    void isHealthyOutsideRecordingHoursOrWhenOff() {
        clock.set(Instant.parse("2026-11-16T01:00:00Z")); // 02:00 in Warsaw
        assertThat(health(true).health().getStatus()).isEqualTo(Status.UP);
        clock.set(PositionRecorderTest.NOON.plusSeconds(600));
        assertThat(health(false).health().getDetails()).containsEntry("recording", "outside recording hours or off");
    }
}
