package com.transitanalytics.timetable;

import com.transitanalytics.domain.RouteSubtype;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The subtype of a route by its name, as live feeds name routes: the route's stored subtype (which may have been set by
 * hand), else the default for the name, for routes missing from the timetable (e.g. other operators' regional routes).
 */
@Component
public class RouteSubtypes {

    /** Stored subtypes are reloaded after this long, so changes and new imports show up. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, RouteSubtype> stored;
    private volatile Instant loadedAt = Instant.MIN;

    public RouteSubtypes(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Null when the route has no name. */
    public RouteSubtype of(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String name = route.strip();
        RouteSubtype subtype = stored().get(name);
        return subtype != null ? subtype : RouteSubtype.classify(name);
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
