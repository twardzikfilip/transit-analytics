package com.transitanalytics.recording;

import com.transitanalytics.zbiorkom.FeedPosition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PositionSampleWriterTest {

    private static final Instant AT = Instant.parse("2026-11-19T12:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PositionSampleWriter writer = new PositionSampleWriter(jdbc);

    private static FeedPosition position(Long id, Instant at, Double bearing, Long delay,
                                         List<FeedPosition.UpcomingStop> stops) {
        return new FeedPosition(id, "230", "BUS", "BKM", null, "18", null, "018-02", "Warzywna", 53.1, 23.2, bearing,
                delay, at, stops);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertsEachPositionWithItsNextStopAndSkipsOnesWithoutVehicleOrTime() throws Exception {
        FeedPosition full = position(7L, AT, 90.4, 30L,
                List.of(new FeedPosition.UpcomingStop(3, "Next", null, null)));
        FeedPosition bare = position(8L, AT, null, null, null);
        given(jdbc.batchUpdate(anyString(), anyList(), anyInt(), org.mockito.ArgumentMatchers.any(
                ParameterizedPreparedStatementSetter.class))).willReturn(new int[][] {{1, 0}});

        int stored = writer.write(List.of(full, bare, position(null, AT, null, null, List.of()),
                position(9L, null, null, null, List.of())), AT);

        assertThat(stored).isEqualTo(1);
        ArgumentCaptor<List<FeedPosition>> rows = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ParameterizedPreparedStatementSetter<FeedPosition>> setter =
                ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(anyString(), rows.capture(), eq(2), setter.capture());
        assertThat(rows.getValue()).containsExactly(full, bare);

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.getValue().setValues(ps, full);
        verify(ps).setTimestamp(1, Timestamp.from(AT));
        verify(ps).setLong(3, 7L);
        verify(ps).setShort(9, (short) 90);
        verify(ps).setInt(10, 30);
        verify(ps).setString(11, "Next");
        verify(ps).setShort(12, (short) 3);

        PreparedStatement empty = mock(PreparedStatement.class);
        setter.getValue().setValues(empty, bare);
        verify(empty).setNull(9, Types.SMALLINT);
        verify(empty).setNull(10, Types.INTEGER);
        verify(empty).setString(11, null);
        verify(empty).setNull(12, Types.SMALLINT);
    }

    @Test
    void writesNothingForNoPositions() {
        assertThat(writer.write(List.of(position(null, AT, null, null, List.of())), null)).isZero();
        verify(jdbc, never()).batchUpdate(anyString(), anyList(), anyInt(),
                org.mockito.ArgumentMatchers.<ParameterizedPreparedStatementSetter<Object>>any());
    }
}
