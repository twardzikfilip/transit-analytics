package com.transitanalytics.gtfs;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads a GTFS feed (zip) into the timetable tables, for any city.
 *
 * <p>Everything runs in one transaction, so readers keep seeing the previous timetable until the new one is
 * complete, and a broken feed changes nothing. Each file is streamed from the zip into a temporary staging table with
 * PostgreSQL {@code COPY}, then merged with SQL:
 * <ul>
 *   <li>agencies, routes, stops and trips are upserted by their GTFS id, so their database ids stay the same
 *       across imports; rows missing from the new feed are deleted;</li>
 *   <li>stop times, calendar dates and shape points are replaced as a whole.</li>
 * </ul>
 */
@Component
public class GtfsImporter {

    private static final Logger log = LoggerFactory.getLogger(GtfsImporter.class);

    /** Guards against zip bombs: no single file may unpack to more than this. */
    static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;

    /** The agency used when a route has no agency_id (allowed when a feed has only one agency). */
    private static final String DEFAULT_AGENCY =
            "(SELECT COALESCE(agency_id, agency_name) FROM gtfs_agency ORDER BY agency_id NULLS FIRST LIMIT 1)";

    private static final String UPSERT_AGENCIES = """
            INSERT INTO agency (gtfs_id, name, url, timezone, lang, phone, fare_url, email)
            SELECT DISTINCT ON (COALESCE(agency_id, agency_name))
                   COALESCE(agency_id, agency_name), agency_name, agency_url, agency_timezone, agency_lang,
                   agency_phone, agency_fare_url, agency_email
            FROM gtfs_agency
            ON CONFLICT (gtfs_id) DO UPDATE SET
                name = EXCLUDED.name, url = EXCLUDED.url, timezone = EXCLUDED.timezone, lang = EXCLUDED.lang,
                phone = EXCLUDED.phone, fare_url = EXCLUDED.fare_url, email = EXCLUDED.email
            """;

    private static final String UPSERT_ROUTES = """
            INSERT INTO route (gtfs_id, agency_id, short_name, long_name, description, route_type, url, color,
                               text_color, sort_order, subtype)
            SELECT DISTINCT ON (r.route_id)
                   r.route_id, a.id, r.route_short_name, r.route_long_name, r.route_desc, r.route_type::smallint,
                   r.route_url,
                   CASE WHEN r.route_color ~ '^[0-9A-Fa-f]{6}$' THEN '#' || upper(r.route_color) END,
                   CASE WHEN r.route_text_color ~ '^[0-9A-Fa-f]{6}$' THEN '#' || upper(r.route_text_color) END,
                   r.route_sort_order::integer,
                   -- Same rule as RouteSubtype.classify. Only for new routes: an existing route keeps its subtype.
                   CASE WHEN coalesce(nullif(r.route_short_name, ''), r.route_long_name) ~* '^N[0-9]+$' THEN 'night_urban'
                        WHEN coalesce(nullif(r.route_short_name, ''), r.route_long_name) ~ '^0*4[0-9]{2}$'
                        THEN 'regional'
                        WHEN coalesce(nullif(r.route_short_name, ''), r.route_long_name) ~ '^0*[1-9][0-9]{2,}$'
                        THEN 'suburban'
                        ELSE 'urban' END
            FROM gtfs_routes r
            LEFT JOIN agency a ON a.gtfs_id = COALESCE(r.agency_id, %s)
            ON CONFLICT (gtfs_id) DO UPDATE SET
                agency_id = EXCLUDED.agency_id, short_name = EXCLUDED.short_name, long_name = EXCLUDED.long_name,
                description = EXCLUDED.description, route_type = EXCLUDED.route_type, url = EXCLUDED.url,
                color = EXCLUDED.color, text_color = EXCLUDED.text_color, sort_order = EXCLUDED.sort_order
            """.formatted(DEFAULT_AGENCY);

    private static final String UPSERT_STOPS = """
            INSERT INTO stop (gtfs_id, code, name, description, latitude, longitude, zone_id, url, location_type,
                              parent_station, wheelchair_boarding, platform_code)
            SELECT DISTINCT ON (stop_id)
                   stop_id, stop_code, COALESCE(stop_name, stop_id), stop_desc, stop_lat::double precision,
                   stop_lon::double precision, zone_id, stop_url, location_type::smallint, parent_station,
                   wheelchair_boarding::smallint, platform_code
            FROM gtfs_stops
            ON CONFLICT (gtfs_id) DO UPDATE SET
                code = EXCLUDED.code, name = EXCLUDED.name, description = EXCLUDED.description,
                latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, zone_id = EXCLUDED.zone_id,
                url = EXCLUDED.url, location_type = EXCLUDED.location_type, parent_station = EXCLUDED.parent_station,
                wheelchair_boarding = EXCLUDED.wheelchair_boarding, platform_code = EXCLUDED.platform_code
            """;

    /** vehicle_id is ours, not GTFS, so an update keeps it. */
    private static final String UPSERT_TRIPS = """
            INSERT INTO trip (gtfs_id, route_id, service_id, headsign, short_name, direction, block_id, shape_id,
                              wheelchair_accessible, bikes_allowed)
            SELECT DISTINCT ON (t.trip_id)
                   t.trip_id, r.id, t.service_id, t.trip_headsign, t.trip_short_name, t.direction_id::smallint,
                   t.block_id, t.shape_id, t.wheelchair_accessible::smallint, t.bikes_allowed::smallint
            FROM gtfs_trips t
            JOIN route r ON r.gtfs_id = t.route_id
            ON CONFLICT (gtfs_id) DO UPDATE SET
                route_id = EXCLUDED.route_id, service_id = EXCLUDED.service_id, headsign = EXCLUDED.headsign,
                short_name = EXCLUDED.short_name, direction = EXCLUDED.direction, block_id = EXCLUDED.block_id,
                shape_id = EXCLUDED.shape_id, wheelchair_accessible = EXCLUDED.wheelchair_accessible,
                bikes_allowed = EXCLUDED.bikes_allowed
            """;

    /** GTFS allows "H:MM:SS"; we store "HH:MM:SS" so times sort as text. */
    private static final String INSERT_STOP_TIMES = """
            INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time, stop_headsign,
                                   pickup_type, drop_off_type, shape_dist_traveled, timepoint)
            SELECT tr.id, s.id, st.stop_sequence::integer, lpad(st.arrival_time, 8, '0'),
                   lpad(st.departure_time, 8, '0'), st.stop_headsign, st.pickup_type::smallint,
                   st.drop_off_type::smallint, st.shape_dist_traveled::double precision, st.timepoint::smallint
            FROM gtfs_stop_times st
            JOIN trip tr ON tr.gtfs_id = st.trip_id
            JOIN stop s ON s.gtfs_id = st.stop_id
            ON CONFLICT (trip_id, stop_sequence) DO NOTHING
            """;

    private static final String INSERT_CALENDAR_DATES = """
            INSERT INTO calendar_date (service_id, date, exception_type)
            SELECT service_id, to_date(date, 'YYYYMMDD'), exception_type::smallint
            FROM gtfs_calendar_dates
            ON CONFLICT (service_id, date) DO NOTHING
            """;

    private static final String INSERT_SHAPE_POINTS = """
            INSERT INTO shape_point (shape_id, sequence, latitude, longitude, shape_dist_traveled)
            SELECT shape_id, shape_pt_sequence::integer, shape_pt_lat::double precision,
                   shape_pt_lon::double precision, shape_dist_traveled::double precision
            FROM gtfs_shapes
            ON CONFLICT (shape_id, sequence) DO NOTHING
            """;

    private static final List<String> TABLES =
            List.of("agency", "route", "stop", "trip", "stop_time", "calendar_date", "shape_point");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public GtfsImporter(DataSource dataSource, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** Result of loading a feed, before the caller adds where it came from. */
    public record Loaded(Map<String, Long> rows, Map<String, Long> skipped, List<String> ignoredFiles) {
    }

    public Loaded load(ZipFile zip) {
        Set<String> known = new java.util.HashSet<>();
        for (GtfsFile file : GtfsFile.values()) {
            known.add(file.fileName);
            if (file.required && entry(zip, file) == null) {
                throw new GtfsFeedException("The feed has no " + file.fileName);
            }
        }
        List<String> ignored = zip.stream().map(ZipEntry::getName)
                .filter(name -> !name.endsWith("/") && !known.contains(name)).sorted().toList();

        try {
            return transaction.execute(status -> {
                Map<GtfsFile, Long> staged = stage(zip);
                Map<String, Long> skipped = merge(staged);
                // ON COMMIT DROP would remove them too, but not if this runs inside a longer transaction.
                for (GtfsFile file : GtfsFile.values()) {
                    jdbc.execute(file.dropStagingTable());
                }
                return new Loaded(countRows(), skipped, ignored);
            });
        } catch (DataAccessException ex) {
            throw new GtfsFeedException("The feed contains invalid data: " + ex.getMostSpecificCause().getMessage(), ex);
        }
    }

    /** Copies every file into its staging table; returns the number of rows read per file. */
    private Map<GtfsFile, Long> stage(ZipFile zip) {
        Map<GtfsFile, Long> staged = new EnumMap<>(GtfsFile.class);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pg = connection.unwrap(PGConnection.class);
            for (GtfsFile file : GtfsFile.values()) {
                jdbc.execute(file.dropStagingTable());
                jdbc.execute(file.createStagingTable());
                ZipEntry entry = entry(zip, file);
                long rows = 0;
                if (entry != null) {
                    if (entry.getSize() > MAX_ENTRY_BYTES) {
                        throw new GtfsFeedException(file.fileName + " is larger than " + MAX_ENTRY_BYTES + " bytes");
                    }
                    try (var in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
                         Writer out = new OutputStreamWriter(new PGCopyOutputStream(pg, file.copyCommand(), 1 << 16),
                                 StandardCharsets.UTF_8)) {
                        rows = GtfsCsv.transcode(in, file.columns, out);
                    }
                }
                jdbc.execute("ANALYZE " + file.stagingTable());
                staged.put(file, rows);
                log.debug("Staged {} rows from {}", rows, file.fileName);
            }
            return staged;
        } catch (SQLException | IOException ex) {
            throw new GtfsFeedException("Could not read the feed: " + ex.getMessage(), ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** Merges the staging tables into the real ones; returns rows skipped per file because of missing references. */
    private Map<String, Long> merge(Map<GtfsFile, Long> staged) {
        Map<String, Long> skipped = new LinkedHashMap<>();

        jdbc.update(UPSERT_AGENCIES);
        jdbc.update(UPSERT_ROUTES);
        jdbc.update("DELETE FROM route x WHERE NOT EXISTS (SELECT 1 FROM gtfs_routes g WHERE g.route_id = x.gtfs_id)");
        jdbc.update("DELETE FROM agency x WHERE NOT EXISTS "
                + "(SELECT 1 FROM gtfs_agency g WHERE COALESCE(g.agency_id, g.agency_name) = x.gtfs_id)");

        jdbc.update(UPSERT_STOPS);
        jdbc.update("DELETE FROM stop x WHERE NOT EXISTS (SELECT 1 FROM gtfs_stops g WHERE g.stop_id = x.gtfs_id)");

        int trips = jdbc.update(UPSERT_TRIPS);
        // Trips whose route is not in the feed were not upserted above; remove them along with stale ones.
        jdbc.update("DELETE FROM trip x WHERE NOT EXISTS (SELECT 1 FROM gtfs_trips g JOIN route r "
                + "ON r.gtfs_id = g.route_id WHERE g.trip_id = x.gtfs_id)");
        note(skipped, GtfsFile.TRIPS, distinct("trip_id", "gtfs_trips") - trips);

        // Fresh statistics, so the stop time insert joins the just-loaded trips and stops efficiently.
        jdbc.execute("ANALYZE route, stop, trip");

        jdbc.update("DELETE FROM stop_time");
        note(skipped, GtfsFile.STOP_TIMES, staged.get(GtfsFile.STOP_TIMES) - jdbc.update(INSERT_STOP_TIMES));

        jdbc.update("DELETE FROM calendar_date");
        note(skipped, GtfsFile.CALENDAR_DATES,
                staged.get(GtfsFile.CALENDAR_DATES) - jdbc.update(INSERT_CALENDAR_DATES));

        jdbc.update("DELETE FROM shape_point");
        note(skipped, GtfsFile.SHAPES, staged.get(GtfsFile.SHAPES) - jdbc.update(INSERT_SHAPE_POINTS));

        // Statistics for the replaced tables, so list pages get good query plans right after the import.
        jdbc.execute("ANALYZE stop_time, calendar_date, shape_point");
        return skipped;
    }

    private long distinct(String column, String table) {
        Long n = jdbc.queryForObject("SELECT count(DISTINCT " + column + ") FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    private static void note(Map<String, Long> skipped, GtfsFile file, long count) {
        if (count > 0) {
            skipped.put(file.fileName, count);
        }
    }

    private Map<String, Long> countRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return Collections.unmodifiableMap(rows);
    }

    private static ZipEntry entry(ZipFile zip, GtfsFile file) {
        return zip.getEntry(file.fileName);
    }
}
