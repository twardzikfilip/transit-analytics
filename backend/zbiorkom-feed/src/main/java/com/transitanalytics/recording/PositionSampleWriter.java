package com.transitanalytics.recording;

import com.transitanalytics.zbiorkom.FeedPosition;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Stores positions in the {@code position_sample} time series. */
public class PositionSampleWriter {

    /** A report already stored (same vehicle, same feed timestamp) is skipped. */
    private static final String INSERT = """
            INSERT INTO position_sample (observed_at, fetched_at, vehicle_id, route_name, brigade, headsign,
                                         latitude, longitude, bearing, delay_seconds, next_stop_name, next_stop_sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (vehicle_id, observed_at) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public PositionSampleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one snapshot in a single batch. Positions without a vehicle id or timestamp are skipped.
     *
     * @return number of new rows
     */
    public int write(List<FeedPosition> positions, Instant fetchedAt) {
        List<FeedPosition> rows = positions.stream()
                .filter(p -> p.vehicleId() != null && p.timestamp() != null)
                .toList();
        if (rows.isEmpty()) {
            return 0;
        }
        int[][] counts = jdbc.batchUpdate(INSERT, rows, rows.size(), (ps, p) -> {
            var next = p.upcomingStops() == null || p.upcomingStops().isEmpty() ? null : p.upcomingStops().get(0);
            ps.setTimestamp(1, Timestamp.from(p.timestamp()));
            ps.setTimestamp(2, fetchedAt == null ? null : Timestamp.from(fetchedAt));
            ps.setLong(3, p.vehicleId());
            ps.setString(4, p.routeName());
            ps.setString(5, p.brigade());
            ps.setString(6, p.headsign());
            ps.setDouble(7, p.latitude());
            ps.setDouble(8, p.longitude());
            if (p.bearing() == null) {
                ps.setNull(9, Types.SMALLINT);
            } else {
                ps.setShort(9, (short) Math.round(p.bearing()));
            }
            if (p.delaySeconds() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setInt(10, Math.toIntExact(p.delaySeconds()));
            }
            ps.setString(11, next == null ? null : next.name());
            if (next == null || next.sequence() == null) {
                ps.setNull(12, Types.SMALLINT);
            } else {
                ps.setShort(12, next.sequence().shortValue());
            }
        });
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).map(n -> Math.max(n, 0)).sum();
    }
}
