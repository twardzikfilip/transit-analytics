package com.transitanalytics.timetable;

import com.transitanalytics.domain.Route;
import com.transitanalytics.domain.RouteCapacity;
import com.transitanalytics.domain.RouteGeometry;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.domain.TimetableFeed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** The timetable's routes. */
@Component
public class Routes {

    private static final String SELECT = """
            SELECT r.id, r.feed, r.gtfs_id, r.short_name, r.long_name, r.color, r.text_color, r.url, r.subtype,
                   r.geometry, r.capacity_class, r.operating_agency, a.name AS agency, a.url AS agency_url
            FROM route r LEFT JOIN agency a ON a.id = r.agency_id""";

    private final JdbcTemplate jdbc;

    public Routes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Route> all() {
        return jdbc.query(SELECT + " ORDER BY r.id", Routes::route);
    }

    /** The route of that GTFS id, BKM's first when other feeds have one too. */
    public Optional<Route> find(String gtfsId) {
        return jdbc.query(SELECT + " WHERE r.gtfs_id = ? ORDER BY r.feed <> ?, r.feed LIMIT 1", Routes::route,
                gtfsId, TimetableFeed.MAIN).stream().findFirst();
    }

    private static Route route(ResultSet rs, int row) throws SQLException {
        String subtype = rs.getString("subtype");
        return new Route(rs.getLong("id"), rs.getString("feed"), rs.getString("gtfs_id"), rs.getString("short_name"),
                rs.getString("long_name"), rs.getString("color"), rs.getString("text_color"), rs.getString("url"),
                subtype == null ? RouteSubtype.URBAN : RouteSubtype.of(subtype),
                RouteGeometry.of(rs.getString("geometry")), RouteCapacity.of(rs.getString("capacity_class")),
                rs.getString("operating_agency"), rs.getString("agency"), rs.getString("agency_url"));
    }
}
