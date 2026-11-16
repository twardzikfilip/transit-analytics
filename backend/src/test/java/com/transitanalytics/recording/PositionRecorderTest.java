package com.transitanalytics.recording;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PositionRecorderTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Instant NOON = Instant.parse("2026-11-16T11:00:00Z"); // 12:00 in Warsaw

    private final ZbiorkomBialystokService feed = mock(ZbiorkomBialystokService.class);
    private final PositionSampleWriter writer = mock(PositionSampleWriter.class);
    private final RawArchive archive = mock(RawArchive.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOON);

    private PositionRecorder recorder(boolean enabled, String importDir) {
        var properties = new RecordingProperties(enabled, "0 * * * * *", WARSAW, LocalTime.of(3, 30),
                LocalTime.of(1, 30), Duration.ofSeconds(55), "unused", importDir);
        return new PositionRecorder(feed, writer, archive,
                new RecordingWindow(properties.windowStart(), properties.windowEnd(), WARSAW), properties, clock,
                meters);
    }

    private static LivePositionDto position(String number) {
        return new LivePositionDto(1L, number, "BUS", "BKM", null, "18", null, "018-02", "Warzywna", 53.1, 23.1,
                90.0, 0L, NOON, List.of(), null);
    }

    @Test
    void storesArchivesAndCountsEachPoll() {
        byte[] raw = "{}".getBytes();
        given(feed.snapshot()).willReturn(new ZbiorkomBialystokService.Snapshot(raw, NOON,
                List.of(position("230"), position("009"))));
        given(writer.write(anyList(), eq(NOON))).willReturn(2);

        assertThat(recorder(true, null).pollIfDue()).isTrue();

        verify(archive).write(PositionRecorder.SOURCE, NOON, raw);
        assertThat(meters.get("transit.recording.samples").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("transit.recording.vehicles").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void neverPollsTwiceWithinTheMinimumInterval() {
        given(feed.snapshot()).willReturn(new ZbiorkomBialystokService.Snapshot(new byte[0], NOON, List.of()));
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.pollIfDue()).isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(recorder.pollIfDue()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(recorder.pollIfDue()).isTrue();
        verify(feed, times(2)).snapshot();
    }

    @Test
    void skipsWhenDisabledOutsideTheWindowOrImporting() {
        assertThat(recorder(false, null).pollIfDue()).isFalse();
        assertThat(recorder(true, "/some/dir").pollIfDue()).isFalse();
        clock.set(Instant.parse("2026-11-16T01:00:00Z")); // 02:00 in Warsaw
        assertThat(recorder(true, null).pollIfDue()).isFalse();
        verify(feed, never()).snapshot();
    }

    @Test
    void countsFailuresAndKeepsGoing() {
        given(feed.snapshot()).willThrow(new ExternalServiceException("zbiorkom.live", new IOException("timeout")));

        assertThat(recorder(true, null).pollIfDue()).isTrue();

        assertThat(meters.get("transit.recording.failures").counter().count()).isEqualTo(1.0);
        verify(writer, never()).write(anyList(), any());
    }

    /** A clock the test can move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
