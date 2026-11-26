package com.transitanalytics.classification;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.RouteCapacity;
import com.transitanalytics.domain.RouteGeometry;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.service.WarmUp;
import com.transitanalytics.service.WeekdayTimetable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies the lines of the configured operators by geometry and capacity ({@code classification/routes.yml}), notes
 * the company operating each ({@code classification/operators.yml}),
 * and stores them on the routes: at startup, after a timetable import and every night.
 */
@Service
public class RouteClasses {

    private static final Logger log = LoggerFactory.getLogger(RouteClasses.class);

    /** Kinds of day the MEGA share is measured on. */
    enum DayType { WEEKDAY, SATURDAY, SUNDAY }

    /** What was worked out for a line: its classes and what they rest on. */
    public record Classified(long routeId, String line, RouteGeometry geometry, RouteCapacity capacity,
                             RouteCapacityClassifier.Inputs inputs, String operatingAgency) {
    }

    private final JdbcTemplate jdbc;
    private final WeekdayTimetable weekdayTimetable;
    private final Clock clock;
    private final RouteClassProperties properties;
    private final OperatorProperties operators;
    private final RouteGeometryClassifier geometry;
    private final RouteCapacityClassifier capacity;

    public RouteClasses(JdbcTemplate jdbc, WeekdayTimetable weekdayTimetable, Clock clock,
                        RouteClassProperties properties, OperatorProperties operators) {
        this.jdbc = jdbc;
        this.operators = operators;
        this.weekdayTimetable = weekdayTimetable;
        this.clock = clock;
        this.properties = properties;
        this.geometry = new RouteGeometryClassifier(properties.geometry(),
                properties.geometry().centreRing(CityBoundaries.CENTRE));
        this.capacity = new RouteCapacityClassifier(properties.capacity());
    }

    /** At startup, in the background, after the buses are classed (the capacity counts the MEGA ones). */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    @Order(1)
    public void atStartup() {
        WarmUp.inBackground("route-classes", RouteClasses.class, this::classifyAll);
    }

    /** After a timetable import: shapes and departures may have changed. */
    @EventListener
    public void afterImport(GtfsImported imported) {
        if (TimetableFeed.RAIL.equals(imported.feed())) {
            return;
        }
        WarmUp.inBackground("route-classes", RouteClasses.class, this::classifyAll);
    }

    /** Every night: the buses running each line change from day to day. */
    @Scheduled(cron = "${app.route-classes.refresh-cron}", zone = CityTime.ZONE_ID)
    public void nightly() {
        classifyAll();
    }

    /** Classifies every line and stores the classes; lines of other operators are left unclassified. */
    public synchronized List<Classified> classifyAll() {
        List<Line> lines = jdbc.query("""
                SELECT r.id, r.gtfs_id, coalesce(nullif(r.short_name, ''), r.long_name) AS name, r.subtype,
                       a.name AS agency
                FROM route r LEFT JOIN agency a ON a.id = r.agency_id""",
                (rs, i) -> new Line(rs.getLong("id"), rs.getString("gtfs_id"), rs.getString("name"),
                        RouteSubtype.of(rs.getString("subtype")), rs.getString("agency")));
        Map<Long, List<double[]>> shapes = mainShapes();
        Map<Long, Map<Integer, List<Integer>>> departures = departures();
        Map<String, Map<DayType, Double>> mega = megaShares();

        List<Classified> classified = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (Line line : lines) {
            RouteGeometry g = null;
            RouteCapacity c = null;
            RouteCapacityClassifier.Inputs inputs = null;
            OperatorProperties.Company company = null;
            if (line.agency() != null && properties.agencies().contains(line.agency())) {
                g = properties.geometry().overrides().getOrDefault(line.name(),
                        geometry.classify(shapes.get(line.id())));
                // Recorded reports name the line by its timetable id.
                Map<DayType, Double> shares = mega.getOrDefault(line.gtfsId(), Map.of());
                Map<Integer, List<Integer>> byDirection = departures.getOrDefault(line.id(), Map.of());
                inputs = capacity.inputs(byDirection.values(), shares.get(DayType.WEEKDAY),
                        shares.get(DayType.SATURDAY), shares.get(DayType.SUNDAY));
                boolean skipped = properties.capacity().skipSubtypes().contains(line.subtype());
                c = skipped ? null : properties.capacity().overrides().getOrDefault(line.name(),
                        byDirection.isEmpty() ? null : capacity.classify(inputs));
                company = operators.companyOf(line.name());
                classified.add(new Classified(line.id(), line.name(), g, c, inputs,
                        company == null ? null : company.code()));
            }
            updates.add(new Object[] {lowercase(g), lowercase(c), company == null ? null : company.code(), line.id()});
        }
        jdbc.batchUpdate("UPDATE route SET geometry = ?, capacity_class = ?, operating_agency = ? WHERE id = ?",
                updates);
        log.info("Lines classified: {} of {} lines", classified.size(), lines.size());
        return classified;
    }

    private record Line(long id, String gtfsId, String name, RouteSubtype subtype, String agency) {
    }

    private static String lowercase(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /** Each line's main route shape: its most common one, preferring the first direction. */
    private Map<Long, List<double[]>> mainShapes() {
        Map<Long, List<double[]>> shapes = new HashMap<>();
        jdbc.query("""
                WITH used AS (
                    SELECT route_id, feed, coalesce(direction, 0) AS dir, shape_id, count(*) AS n
                    FROM trip WHERE shape_id IS NOT NULL GROUP BY 1, 2, 3, 4),
                main AS (
                    SELECT DISTINCT ON (route_id) route_id, feed, shape_id FROM used
                    ORDER BY route_id, (dir <> 0), n DESC, shape_id)
                SELECT m.route_id, sp.latitude, sp.longitude
                FROM main m JOIN shape_point sp ON sp.feed = m.feed AND sp.shape_id = m.shape_id
                ORDER BY m.route_id, sp.sequence""",
                rs -> {
                    shapes.computeIfAbsent(rs.getLong("route_id"), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")});
                });
        return shapes;
    }

    /**
     * Each line's departures (first stop of each trip, seconds after midnight) per direction, on the weekday service;
     * a line without weekday service (night lines) on the service it runs most.
     */
    private Map<Long, Map<Integer, List<Integer>>> departures() {
        Map<Long, Map<Integer, List<Integer>>> departures = new HashMap<>();
        jdbc.query("""
                WITH services AS (
                    SELECT route_id, feed, service_id, count(*) AS n FROM trip GROUP BY 1, 2, 3),
                chosen AS (
                    SELECT DISTINCT ON (route_id) route_id, service_id FROM services
                    ORDER BY route_id, (feed = ? AND service_id IS NOT DISTINCT FROM ?) DESC, n DESC, service_id)
                SELECT t.route_id, coalesce(t.direction, 0) AS dir,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS departure_s
                FROM trip t
                JOIN chosen c ON c.route_id = t.route_id AND c.service_id = t.service_id
                JOIN stop_time st ON st.trip_id = t.id
                GROUP BY t.id, t.route_id, t.direction""",
                rs -> {
                    departures.computeIfAbsent(rs.getLong("route_id"), k -> new HashMap<>())
                            .computeIfAbsent(rs.getInt("dir"), k -> new ArrayList<>())
                            .add(rs.getInt("departure_s"));
                },
                TimetableFeed.MAIN, weekdayTimetable.serviceId());
        return departures;
    }

    /** Per line name and day type, the share of position reports made by MEGA buses (buses of a known class). */
    private Map<String, Map<DayType, Double>> megaShares() {
        Map<String, Map<DayType, Double>> shares = new HashMap<>();
        jdbc.query("""
                SELECT p.route_name,
                       CASE extract(isodow FROM p.observed_at AT TIME ZONE '%s')
                            WHEN 6 THEN 'SATURDAY' WHEN 7 THEN 'SUNDAY' ELSE 'WEEKDAY' END AS day_type,
                       avg(CASE WHEN v.vehicle_class = 'mega' THEN 1.0 ELSE 0.0 END) AS share
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE p.observed_at >= ? AND p.route_name IS NOT NULL AND v.vehicle_class IS NOT NULL
                GROUP BY 1, 2""".formatted(CityTime.ZONE_ID),
                rs -> {
                    shares.computeIfAbsent(rs.getString("route_name"), k -> new EnumMap<>(DayType.class))
                            .put(DayType.valueOf(rs.getString("day_type")), rs.getDouble("share"));
                },
                Timestamp.from(clock.instant().minus(Duration.ofDays(properties.capacity().lookbackDays()))));
        return shares;
    }
}
