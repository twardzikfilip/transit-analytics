package com.transitanalytics.service;

import com.transitanalytics.domain.RouteSubtype;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineSubtypesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    private final LineSubtypes subtypes = new LineSubtypes(jdbc, clock);

    @Test
    void usesTheStoredSubtypeElseTheDefaultForTheName() throws SQLException {
        // Line 22 was set to regional by hand; 406 is another operator's line, not in the timetable.
        routes(Map.of("22", "regional", "103", "suburban"));

        assertThat(subtypes.of("22")).isEqualTo(RouteSubtype.REGIONAL);
        assertThat(subtypes.of(" 103 ")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(subtypes.of("406")).isEqualTo(RouteSubtype.REGIONAL);
        assertThat(subtypes.of("N2")).isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(subtypes.of(null)).isNull();
    }

    @Test
    void reloadsTheStoredSubtypesAfterAWhile() throws SQLException {
        routes(Map.of("22", "regional"));
        subtypes.of("22");
        subtypes.of("22");
        verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));

        clock.now = clock.now.plus(LineSubtypes.MAX_AGE).plusSeconds(1);
        subtypes.of("22");
        verify(jdbc, times(2)).query(anyString(), any(RowCallbackHandler.class));
    }

    private void routes(Map<String, String> subtypeByName) throws SQLException {
        willAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (var e : subtypeByName.entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("name")).thenReturn(e.getKey());
                when(rs.getString("subtype")).thenReturn(e.getValue());
                handler.processRow(rs);
            }
            return null;
        }).given(jdbc).query(anyString(), any(RowCallbackHandler.class));
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
