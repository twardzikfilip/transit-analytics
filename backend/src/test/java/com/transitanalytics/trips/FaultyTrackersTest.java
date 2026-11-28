package com.transitanalytics.trips;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.trips.TripAnomalies.Kind;
import com.transitanalytics.trips.TripAnomalies.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultyTrackersTest {

    private static final Instant MONDAY = Instant.parse("2099-01-05T08:00:00Z");

    private static Outcome trip(int day, String route, Kind kind, long bus) {
        Instant departure = MONDAY.plusSeconds(day * 86_400L);
        return new Outcome("t", route, departure, departure.plusSeconds(1800), kind, null, List.of(bus));
    }

    @Test
    void listsBusesLosingTheirSignalOnSeveralDaysAndOftenEnough() throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (int day = 0; day < 4; day++) {
            // Bus 1: two no-signal trips a day of ten.
            outcomes.add(trip(day, day % 2 == 0 ? "6" : "104", Kind.NO_SIGNAL, 1));
            outcomes.add(trip(day, "6", Kind.NO_SIGNAL, 1));
            for (int i = 0; i < 8; i++) {
                outcomes.add(trip(day, "6", Kind.COMPLETED, 1));
            }
            // Bus 2: one no-signal trip a day of forty: chance.
            outcomes.add(trip(day, "13", Kind.NO_SIGNAL, 2));
            for (int i = 0; i < 39; i++) {
                outcomes.add(trip(day, "13", Kind.COMPLETED, 2));
            }
        }
        // Bus 3: many, on two days only; and trips not run don't count as its.
        for (int i = 0; i < 6; i++) {
            outcomes.add(trip(i % 2, "20", Kind.NO_SIGNAL, 3));
            outcomes.add(trip(i % 2, "20", Kind.MISSED, 3));
        }

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getString("vehicle_number")).thenReturn("046");
        doAnswer(call -> {
            call.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        List<TripAnomalyDto.FaultyTracker> faulty = new FaultyTrackers(jdbc, null, null).faulty(outcomes);

        assertThat(faulty).singleElement().satisfies(f -> {
            assertThat(f.vehicleId()).isEqualTo(1);
            assertThat(f.bus()).isEqualTo("046");
            assertThat(f.noSignalTrips()).isEqualTo(8);
            assertThat(f.trips()).isEqualTo(40);
            assertThat(f.days()).isEqualTo(4);
            assertThat(f.routes()).containsExactly("6", "104");
            assertThat(f.lastNoSignal()).isEqualTo(MONDAY.plusSeconds(3 * 86_400L));
        });
        assertThat(new FaultyTrackers(jdbc, null, null).faulty(List.of())).isEmpty();
    }
}
