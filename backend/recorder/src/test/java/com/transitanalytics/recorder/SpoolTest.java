package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.FeedPosition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpoolTest {

    private static final Instant T1 = Instant.parse("2026-11-16T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-16T11:00:10Z");

    @TempDir
    Path dir;

    private final BialystokFeed feed = mock(BialystokFeed.class);
    private final PositionSampleWriter writer = mock(PositionSampleWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Spool spool() {
        var properties = new RecordingProperties(true, "*/10 * * * * *", PositionRecorderTest.WARSAW,
                LocalTime.of(3, 30), LocalTime.of(1, 30), Duration.ofSeconds(8), "unused",
                dir.resolve("spool").toString(), null);
        return new Spool(properties, feed, writer, meters);
    }

    private Path response(String name, String body) throws Exception {
        return Files.writeString(dir.resolve(name), body);
    }

    @Test
    void storesSpooledResponsesOldestFirstOnceTheDatabaseIsBack() throws Exception {
        Path a = response("a.json", "{\"a\":1}");
        Path b = response("b.json", "{\"b\":2}");
        List<FeedPosition> first = List.of(PositionRecorderTest.position(1L, "230"));
        List<FeedPosition> second = List.of(PositionRecorderTest.position(2L, "009"));
        Spool spool = spool();
        spool.add(a, T1);
        spool.add(b, T2);
        assertThat(spool.size()).isEqualTo(2);
        assertThat(meters.get("transit.recording.spooled").gauge().value()).isEqualTo(2.0);

        // Still down: the first fails, so nothing is stored and both wait.
        given(feed.positions(any())).willThrow(new DataAccessResourceFailureException("down"));
        assertThat(spool.replay()).isZero();
        assertThat(spool.size()).isEqualTo(2);

        // Back: both, in turn.
        org.mockito.Mockito.reset(feed);
        given(feed.positions("{\"a\":1}".getBytes())).willReturn(first);
        given(feed.positions("{\"b\":2}".getBytes())).willReturn(second);
        given(writer.write(any(), any())).willReturn(1);
        assertThat(spool.replay()).isEqualTo(2);

        verify(writer).write(first, T1);
        verify(writer).write(second, T2);
        assertThat(spool.size()).isZero();
        assertThat(dir.resolve("spool/pending")).doesNotExist();
    }

    @Test
    void keepsTheRestWhenARoundStopsHalfWay() throws Exception {
        Path a = response("a.json", "{\"a\":1}");
        Path b = response("b.json", "{\"b\":2}");
        Spool spool = spool();
        spool.add(a, T1);
        spool.add(b, T2);
        given(feed.positions("{\"a\":1}".getBytes())).willReturn(List.of());
        given(feed.positions("{\"b\":2}".getBytes())).willThrow(new DataAccessResourceFailureException("down again"));

        assertThat(spool.replay()).isEqualTo(1);

        assertThat(Files.readAllLines(dir.resolve("spool/pending"))).containsExactly(T2 + "\t" + b.toAbsolutePath());
    }

    @Test
    void skipsResponsesThatAreGoneAndLinesItCannotRead() throws Exception {
        Spool spool = spool();
        spool.add(dir.resolve("deleted.json"), T1);
        Files.writeString(dir.resolve("spool/pending"), "garbage\nnot-a-time\t/x.json\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(spool.replay()).isEqualTo(3);
        assertThat(spool.size()).isZero();
        spool.atStartup();
    }
}
