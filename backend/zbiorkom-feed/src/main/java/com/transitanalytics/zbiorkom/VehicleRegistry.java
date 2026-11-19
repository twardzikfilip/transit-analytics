package com.transitanalytics.zbiorkom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ids of vehicles in the {@code vehicle} table, adding the ones seen for the first time. Safe when two apps (or
 * two polls) add the same vehicle at once: an insert that loses the race is skipped and the id is read back.
 * The vehicle's size class and capacity are left for the backend to fill in from its model.
 */
public class VehicleRegistry {

    /** A vehicle as seen on the feed. */
    public record Discovered(String vehicleNumber, String type, String agency, String model) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public VehicleRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Vehicle id per number, for every vehicle given. */
    public Map<String, Long> idsFor(Collection<Discovered> vehicles) {
        Map<String, Discovered> byNumber = new LinkedHashMap<>();
        vehicles.forEach(v -> byNumber.putIfAbsent(v.vehicleNumber(), v));
        if (byNumber.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = known(byNumber.keySet());
        List<Discovered> missing = byNumber.values().stream().filter(v -> !ids.containsKey(v.vehicleNumber())).toList();
        if (!missing.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO vehicle (vehicle_number, type, agency, model) VALUES (?, ?, ?, ?)
                    ON CONFLICT (vehicle_number) DO NOTHING""", missing, missing.size(), (ps, v) -> {
                ps.setString(1, v.vehicleNumber());
                ps.setString(2, v.type());
                ps.setString(3, v.agency());
                ps.setString(4, v.model());
            });
            ids.putAll(known(missing.stream().map(Discovered::vehicleNumber).toList()));
        }
        return ids;
    }

    private Map<String, Long> known(Collection<String> numbers) {
        Map<String, Long> ids = new HashMap<>();
        named.query("SELECT id, vehicle_number FROM vehicle WHERE vehicle_number IN (:numbers)",
                new MapSqlParameterSource("numbers", numbers),
                rs -> {
                    ids.put(rs.getString("vehicle_number"), rs.getLong("id"));
                });
        return ids;
    }
}
