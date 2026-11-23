package com.transitanalytics.service;

import com.transitanalytics.domain.RouteSubtype;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The subtype of a line by its name, as live feeds name lines: the route's stored subtype (which may have been set by
 * hand), else the default for the name, for lines missing from the timetable (e.g. other operators' regional lines).
 */
@Component
public class LineSubtypes {

    /** Stored subtypes are reloaded after this long, so changes and new imports show up. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, RouteSubtype> stored;
    private volatile Instant loadedAt = Instant.MIN;

    public LineSubtypes(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Null when the line has no name. */
    public RouteSubtype of(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String name = line.strip();
        RouteSubtype subtype = stored().get(name);
        return subtype != null ? subtype : RouteSubtype.classify(name);
    }

    /** Every line of the timetable by name, with its subtype. */
    public Map<String, RouteSubtype> all() {
        return Collections.unmodifiableMap(new TreeMap<>(stored()));
    }

    private Map<String, RouteSubtype> stored() {
        Instant now = clock.instant();
        if (stored == null || loadedAt.plus(MAX_AGE).isBefore(now)) {
            Map<String, RouteSubtype> byName = new HashMap<>();
            jdbc.query("SELECT coalesce(nullif(short_name, ''), long_name) AS name, subtype FROM route",
                    rs -> {
                        String name = rs.getString("name");
                        if (name != null) {
                            byName.putIfAbsent(name.strip(), RouteSubtype.of(rs.getString("subtype")));
                        }
                    });
            stored = byName;
            loadedAt = now;
        }
        return stored;
    }
}
