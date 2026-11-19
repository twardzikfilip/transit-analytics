package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.recording.RawArchive;
import com.transitanalytics.recording.RecordingWindow;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.FeedPosition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PositionRecorderTest {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    static final Instant NOON = Instant.parse("2026-11-16T11:00:00Z"); // 12:00 in Warsaw
    private static final byte[] RAW = "{}".getBytes();
    private static final Path FILE = Path.of("/archive/20261116T110000Z.json.gz");

    private final BialystokFeed feed = mock(BialystokFeed.class);
    private final PositionSampleWriter writer = mock(PositionSampleWriter.class);
    private final RawArchive archive = mock(RawArchive.class);
    private final Spool spool = mock(Spool.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOON);

    static RecordingProperties properties(boolean enabled, String importDir) {
        return new RecordingProperties(enabled, "*/10 * * * * *", WARSAW, LocalTime.of(3, 30), LocalTime.of(1, 30),
                Duration.ofSeconds(8), "unused", "unused", importDir);
    }

    @org.junit.jupiter.api.BeforeEach
    void passResponsesThrough() {
        given(feed.withoutExcluded(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private PositionRecorder recorder(boolean enabled, String importDir) {
        var properties = properties(enabled, importDir);
        return new PositionRecorder(feed, writer, archive, spool,
                new RecordingWindow(properties.windowStart(), properties.windowEnd(), WARSAW), properties, clock,
                meters);
    }

    static FeedPosition position(Long id, String number) {
        return new FeedPosition(id, number, "BUS", "BKM", null, "18", null, "018-02", "Warzywna", 53.1, 23.1, 90.0,
                0L, NOON, List.of());
    }

    @Test
    void archivesFirstThenStoresAndCountsEachPoll() {
        given(feed.fetchRaw()).willReturn(RAW);
        given(archive.write(PositionRecorder.SOURCE, NOON, RAW)).willReturn(FILE);
        List<FeedPosition> positions = List.of(position(1L, "230"), position(2L, "009"));
        given(feed.positions(RAW)).willReturn(positions);
        given(writer.write(positions, NOON)).willReturn(2);
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.pollIfDue()).isTrue();

        var order = inOrder(archive, feed, writer);
        order.verify(archive).write(PositionRecorder.SOURCE, NOON, RAW);
        order.verify(feed).positions(RAW);
        order.verify(writer).write(positions, NOON);
        assertThat(meters.get("transit.recording.samples").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("transit.recording.vehicles").gauge().value()).isEqualTo(2.0);
        assertThat(recorder.lastPoll()).isEqualTo(NOON);
        assertThat(recorder.lastStored()).isEqualTo(NOON);
        // The Live map gets these positions, without asking the feed again.
        assertThat(recorder.live().positions()).isEqualTo(positions);
        verify(feed, times(1)).fetchRaw();
        verify(spool, never()).add(any(), any());
    }

    @Test
    void spoolsTheResponseWhenTheDatabaseIsDown() {
        given(feed.fetchRaw()).willReturn(RAW);
        given(archive.write(PositionRecorder.SOURCE, NOON, RAW)).willReturn(FILE);
        given(feed.positions(RAW)).willThrow(new DataAccessResourceFailureException("connection refused"));
        given(feed.positionsWithoutIds(RAW)).willReturn(List.of(position(null, "230")));
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.pollIfDue()).isTrue();

        verify(spool).add(FILE, NOON);
        assertThat(meters.get("transit.recording.failures").counter().count()).isEqualTo(1.0);
        assertThat(recorder.lastStored()).isNull();
        // The Live map still shows where the buses are.
        assertThat(recorder.live().positions()).extracting(FeedPosition::vehicleNumber).containsExactly("230");
    }

    @Test
    void storesEvenWhenArchivingFailsButLosesItIfBothFail() {
        given(feed.fetchRaw()).willReturn(RAW);
        given(archive.write(PositionRecorder.SOURCE, NOON, RAW)).willThrow(new IllegalStateException("disk full"));
        given(feed.positions(RAW)).willReturn(List.of(position(1L, "230"))).willThrow(new IllegalStateException("db"));
        given(feed.positionsWithoutIds(RAW)).willThrow(new IllegalStateException("bad"));
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.pollIfDue()).isTrue();
        verify(writer).write(anyList(), eq(NOON));
        clock.advance(Duration.ofSeconds(10));
        assertThat(recorder.pollIfDue()).isTrue();
        verify(spool, never()).add(any(), any());
        assertThat(recorder.live().positions()).as("the last positions known").hasSize(1);
    }

    @Test
    void neverPollsTwiceWithinTheMinimumInterval() {
        given(feed.fetchRaw()).willReturn(RAW);
        given(feed.positions(RAW)).willReturn(List.of());
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.pollIfDue()).isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(recorder.pollIfDue()).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(recorder.pollIfDue()).isTrue();
        verify(feed, times(2)).fetchRaw();
    }

    @Test
    void skipsWhenDisabledOutsideTheWindowOrImporting() {
        assertThat(recorder(false, null).pollIfDue()).isFalse();
        assertThat(recorder(true, "/some/dir").pollIfDue()).isFalse();
        clock.set(Instant.parse("2026-11-16T01:00:00Z")); // 02:00 in Warsaw
        assertThat(recorder(true, null).pollIfDue()).isFalse();
        verify(feed, never()).fetchRaw();
    }

    @Test
    void countsFailedPollsAndKeepsGoing() {
        given(feed.fetchRaw()).willThrow(new ExternalServiceException("zbiorkom.live", new IOException("timeout")));

        assertThat(recorder(true, null).pollIfDue()).isTrue();

        assertThat(meters.get("transit.recording.failures").counter().count()).isEqualTo(1.0);
        verify(archive, never()).write(any(), any(), any());
        verify(writer, never()).write(anyList(), any());
    }

    @Test
    void fetchesForTheLiveMapWhenNothingRecentIsKnownAtMostOnceInTheInterval() {
        // Outside recording hours: nothing polled.
        clock.set(Instant.parse("2026-11-16T01:00:00Z"));
        given(feed.fetchRaw()).willReturn(RAW);
        given(feed.positions(RAW)).willReturn(List.of(position(1L, "230")));
        PositionRecorder recorder = recorder(true, null);

        assertThat(recorder.live().positions()).hasSize(1);
        clock.advance(Duration.ofSeconds(5));
        assertThat(recorder.live().fetchedAt()).as("the same one").isEqualTo(Instant.parse("2026-11-16T01:00:00Z"));
        clock.advance(Duration.ofSeconds(40));
        given(feed.positions(RAW)).willThrow(new IllegalStateException("db down"));
        given(feed.positionsWithoutIds(RAW)).willReturn(List.of(position(null, "230")));
        assertThat(recorder.live().positions()).extracting(FeedPosition::vehicleId).containsOnlyNulls();
        verify(feed, times(2)).fetchRaw();
        verify(writer, never()).write(anyList(), any());

        given(feed.fetchRaw()).willThrow(new ExternalServiceException("zbiorkom.live", new IOException("down")));
        clock.advance(Duration.ofSeconds(40));
        assertThatThrownBy(recorder::live).isInstanceOf(ExternalServiceException.class);
    }
}
