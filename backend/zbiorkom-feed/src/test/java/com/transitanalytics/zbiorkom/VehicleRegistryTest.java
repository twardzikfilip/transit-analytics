package com.transitanalytics.zbiorkom;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleRegistryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** The database knows vehicle 230, id 1. */
    private void database() {
        willAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("vehicle_number")).thenReturn("230");
            when(rs.getLong("id")).thenReturn(1L);
            handler.processRow(rs);
            return null;
        }).given(jdbc).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsOnlyTheNewVehiclesOnceAndReturnsEveryId() throws Exception {
        VehicleRegistry registry = new VehicleRegistry(jdbc);
        var known = new VehicleRegistry.Discovered("230", "BUS", "BKM", "Solaris Urbino 12");
        var fresh = new VehicleRegistry.Discovered("009", "BUS", "BKM", "Yutong ZK6128BEVG");
        // Known first: 230; after the insert, the read-back finds the new one.
        willAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("vehicle_number")).thenReturn("230");
            when(rs.getLong("id")).thenReturn(1L);
            handler.processRow(rs);
            return null;
        }).willAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("vehicle_number")).thenReturn("009");
            when(rs.getLong("id")).thenReturn(10L);
            handler.processRow(rs);
            return null;
        }).given(jdbc).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

        Map<String, Long> ids = registry.idsFor(List.of(known, fresh, fresh));

        assertThat(ids).containsExactlyInAnyOrderEntriesOf(Map.of("230", 1L, "009", 10L));
        ArgumentCaptor<List<VehicleRegistry.Discovered>> added = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ParameterizedPreparedStatementSetter<VehicleRegistry.Discovered>> setter =
                ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(anyString(), added.capture(), anyInt(), setter.capture());
        assertThat(added.getValue()).containsExactly(fresh);
        PreparedStatement ps = mock(PreparedStatement.class);
        setter.getValue().setValues(ps, fresh);
        verify(ps).setString(1, "009");
        verify(ps).setString(4, "Yutong ZK6128BEVG");
    }

    @Test
    void asksNothingForNoVehiclesAndInsertsNothingWhenAllAreKnown() throws Exception {
        VehicleRegistry registry = new VehicleRegistry(jdbc);
        assertThat(registry.idsFor(List.of())).isEmpty();
        database();
        assertThat(registry.idsFor(List.of(new VehicleRegistry.Discovered("230", "BUS", "BKM", null))))
                .containsExactly(Map.entry("230", 1L));
        verify(jdbc, never()).batchUpdate(anyString(), anyList(), anyInt(),
                org.mockito.ArgumentMatchers.<ParameterizedPreparedStatementSetter<Object>>any());
        verify(jdbc, never()).update(anyString(), any(PreparedStatementSetter.class));
    }
}
