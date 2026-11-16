package com.transitanalytics.recording;

import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Reads the recorded position time series ({@code position_sample}), for the city operator's (BKM) lines. */
@Service
public class PositionHistoryService {

    /** Longest window one request may cover. */
    static final Duration MAX_WINDOW = Duration.ofDays(31);
    /**
     * Bucket sizes to choose from; the smallest that keeps a chart at or under {@link #MAX_POINTS} points wins:
     * 1 minute (one poll per point) up to 24 hours, 10 minutes for a week, 30 minutes for a month.
     */
    static final List<Duration> STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3),
            Duration.ofHours(6), Duration.ofDays(1));
    static final int MAX_POINTS = 1500;
    static final int MAX_PAGE_SIZE = 500;
    /**
     * Reports more than this late are treated as outliers (likely feed errors). The frontend uses the same 30 minutes
     * to highlight them.
     */
    static final int OUTLIER_DELAY_SECONDS = DelayOutliers.THRESHOLD_SECONDS;
    /** Delay bands are whole minutes from 1 up to this; below is "under 1 minute", from here on "or more". */
    static final int DELAY_BANDS_UP_TO_MINUTES = 10;

    /**
     * Rows of polls made in [from, to). A poll is identified by {@code fetched_at}; imported files without one use the
     * report's own time. The extra condition on {@code observed_at} (the hypertable's time column, with slack for
     * clock differences) lets TimescaleDB skip chunks outside the window.
     */
    private static String windowCondition(String alias) {
        String p = alias.isEmpty() ? "" : alias + ".";
        return "%1$sobserved_at >= ? AND %1$sobserved_at < ?".formatted(p)
                + " AND coalesce(%1$sfetched_at, %1$sobserved_at) >= ? AND coalesce(%1$sfetched_at, %1$sobserved_at) < ?"
                .formatted(p)
                // City lines only: regional operators (e.g. WSCHODEXPRESS, TUROSN) run their own lines on the feed.
                + " AND %svehicle_id IN (SELECT id FROM vehicle WHERE agency = '%s')".formatted(p, AGENCY);
    }

    /**
     * Position history covers the city operator's lines only. Every line in the feed is run by a single operator,
     * so filtering by the vehicle's operator removes exactly the regional lines.
     */
    static final String AGENCY = ZbiorkomBialystokService.CITY_AGENCY;

    private static final String WINDOW = windowCondition("");
    private static final Duration SLACK = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    public PositionHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Vehicles running and mean delay per bucket, plus totals, for polls in {@code [from, to)}. */
    public PositionHistoryDto.FleetSeries fleet(Instant from, Instant to, String route) {
        validate(from, to);
        Duration step = stepFor(from, to);
        String routeFilter = route == null ? "" : " AND route_name = ?";

        List<Object> args = new ArrayList<>(window(from, to, route));
        args.add(step.toSeconds());
        // Per poll first (one row per minute), then per bucket: vehicles = average per poll, delay = mean of reports.
        // Buckets are aligned to the epoch (UTC), so they stay the same when the window moves.
        List<PositionHistoryDto.FleetPoint> points = jdbc.query("""
                WITH polls AS (
                    SELECT coalesce(fetched_at, observed_at) AS poll, count(*) AS vehicles,
                           sum(delay_seconds) AS delay_sum, count(delay_seconds) AS delay_count
                    FROM position_sample
                    WHERE """ + " " + WINDOW + routeFilter + """

                    GROUP BY 1)
                SELECT time_bucket(make_interval(secs => ?::integer), poll) AS bucket,
                       avg(vehicles)::double precision AS vehicles, max(vehicles) AS peak, sum(vehicles) AS samples,
                       sum(delay_sum)::double precision / nullif(sum(delay_count), 0) AS mean_delay
                FROM polls
                GROUP BY bucket ORDER BY bucket""",
                (rs, i) -> new PositionHistoryDto.FleetPoint(instant(rs, "bucket"), rs.getDouble("vehicles"),
                        rs.getLong("peak"), rs.getLong("samples"), nullableDouble(rs, "mean_delay")),
                args.toArray());

        PositionHistoryDto.Summary summary = jdbc.queryForObject("""
                WITH r AS (
                    SELECT vehicle_id, observed_at, delay_seconds, coalesce(fetched_at, observed_at) AS poll
                    FROM position_sample
                    WHERE """ + " " + WINDOW + routeFilter + """
                ),
                polls AS (SELECT poll, count(*) AS n FROM r GROUP BY poll)
                SELECT (SELECT count(*) FROM r) AS samples,
                       (SELECT count(DISTINCT vehicle_id) FROM r) AS vehicles,
                       (SELECT coalesce(max(n), 0) FROM polls) AS peak,
                       (SELECT n FROM polls ORDER BY poll DESC LIMIT 1) AS latest,
                       (SELECT max(poll) FROM polls) AS latest_poll,
                       (SELECT avg(delay_seconds)::double precision FROM r) AS mean_delay,
                       (SELECT min(observed_at) FROM r) AS first,
                       (SELECT max(observed_at) FROM r) AS last""",
                (rs, i) -> new PositionHistoryDto.Summary(rs.getLong("samples"), rs.getLong("vehicles"),
                        rs.getLong("peak"), rs.getLong("latest"), instant(rs, "latest_poll"),
                        nullableDouble(rs, "mean_delay"), instant(rs, "first"), instant(rs, "last")),
                window(from, to, route).toArray());

        return new PositionHistoryDto.FleetSeries(from, to, step.toSeconds(), route, points, summary);
    }

    /**
     * How many vehicles and reports fall in each delay band: under 1 minute (on time or early), 1–2, 2–3, … 9–10,
     * and 10 minutes or more. Reports without a delay are left out.
     */
    public List<PositionHistoryDto.DelayBand> delays(Instant from, Instant to, String route) {
        validate(from, to);
        String routeFilter = route == null ? "" : " AND route_name = ?";
        int top = DELAY_BANDS_UP_TO_MINUTES;
        long[][] counts = new long[top + 1][2];
        jdbc.query("""
                SELECT CASE WHEN delay_seconds < 60 THEN 0
                            WHEN delay_seconds >= %d THEN %d
                            ELSE delay_seconds / 60 END AS band,
                       count(DISTINCT vehicle_id) AS vehicles, count(*) AS reports
                FROM position_sample
                WHERE %s AND delay_seconds IS NOT NULL%s
                GROUP BY band""".formatted(top * 60, top, WINDOW, routeFilter),
                rs -> {
                    int band = rs.getInt("band");
                    counts[band][0] = rs.getLong("vehicles");
                    counts[band][1] = rs.getLong("reports");
                },
                window(from, to, route).toArray());
        List<PositionHistoryDto.DelayBand> bands = new ArrayList<>();
        for (int band = 0; band <= top; band++) {
            bands.add(new PositionHistoryDto.DelayBand(band == 0 ? null : band, band == top ? null : band + 1,
                    counts[band][0], counts[band][1]));
        }
        return bands;
    }

    /** Longest window a replay may cover. */
    static final Duration MAX_REPLAY_WINDOW = Duration.ofDays(7);
    /** Frame steps to choose from; the smallest keeping a replay at or under {@link #MAX_FRAMES} frames wins. */
    static final List<Duration> REPLAY_STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30));
    static final int MAX_FRAMES = 360;

    /**
     * Recorded positions in {@code [from, to)} as animation frames: per step, the latest report of every vehicle in
     * the polls of that step. Optionally only some lines.
     */
    public PositionHistoryDto.Replay replay(Instant from, Instant to, List<String> routes) {
        validate(from, to);
        if (Duration.between(from, to).compareTo(MAX_REPLAY_WINDOW) > 0) {
            throw new IllegalArgumentException("A replay can cover at most " + MAX_REPLAY_WINDOW.toDays() + " days");
        }
        List<String> lineFilter = routes == null ? List.of()
                : routes.stream().filter(r -> r != null && !r.isBlank()).map(String::strip).distinct().toList();
        Duration step = REPLAY_STEPS.stream().filter(s -> Duration.between(from, to).dividedBy(s) <= MAX_FRAMES)
                .findFirst().orElse(REPLAY_STEPS.get(REPLAY_STEPS.size() - 1));

        List<Object> args = new ArrayList<>(List.of(step.toSeconds()));
        args.addAll(window(from, to, null));
        String routeFilter = "";
        if (!lineFilter.isEmpty()) {
            routeFilter = " AND p.route_name IN (" + String.join(", ", java.util.Collections.nCopies(lineFilter.size(), "?")) + ")";
            args.addAll(lineFilter);
        }

        Map<String, Integer> vehicleIndex = new java.util.LinkedHashMap<>();
        Map<String, Integer> lineIndex = new java.util.LinkedHashMap<>();
        java.util.TreeMap<Instant, List<List<Object>>> frames = new java.util.TreeMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (bucket, p.vehicle_id)
                       time_bucket(make_interval(secs => ?::integer), coalesce(p.fetched_at, p.observed_at)) AS bucket,
                       v.vehicle_number, p.route_name, p.latitude, p.longitude, p.delay_seconds
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE""" + " " + windowCondition("p") + routeFilter + """

                ORDER BY bucket, p.vehicle_id, p.observed_at DESC""",
                rs -> {
                    int vehicle = vehicleIndex.computeIfAbsent(rs.getString("vehicle_number"), k -> vehicleIndex.size());
                    String route = rs.getString("route_name");
                    Integer line = route == null ? null : lineIndex.computeIfAbsent(route, k -> lineIndex.size());
                    List<Object> row = new ArrayList<>(5);
                    row.add(vehicle);
                    row.add(round(rs.getDouble("latitude"), 6));
                    row.add(round(rs.getDouble("longitude"), 6));
                    row.add(nullableInt(rs, "delay_seconds"));
                    row.add(line);
                    frames.computeIfAbsent(instant(rs, "bucket"), k -> new ArrayList<>()).add(row);
                },
                args.toArray());

        return new PositionHistoryDto.Replay(from, to, step.toSeconds(), lineFilter,
                List.copyOf(vehicleIndex.keySet()), List.copyOf(lineIndex.keySet()),
                frames.entrySet().stream().map(e -> new PositionHistoryDto.Frame(e.getKey(), e.getValue())).toList());
    }

    private static double round(double value, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(value * f) / f;
    }

    /** Lines recorded in the window, in natural line order (2, 10, 100, N1). */
    public List<PositionHistoryDto.RouteActivity> routes(Instant from, Instant to) {
        validate(from, to);
        List<PositionHistoryDto.RouteActivity> routes = jdbc.query("""
                SELECT route_name, count(*) AS samples
                FROM position_sample
                WHERE """ + " " + WINDOW + """
                 AND route_name IS NOT NULL
                GROUP BY route_name""",
                (rs, i) -> new PositionHistoryDto.RouteActivity(rs.getString("route_name"), rs.getLong("samples")),
                window(from, to, null).toArray());
        return routes.stream().sorted(Comparator.comparing(PositionHistoryDto.RouteActivity::route, NATURAL)).toList();
    }

    /**
     * Columns the reports can be sorted by. Vehicle numbers and lines sort naturally (2, 10, 100, then names with
     * letters such as N1 or BIA 3488C).
     */
    enum SampleSort {
        TIME("p.observed_at"),
        VEHICLE(natural("v.vehicle_number")),
        LINE(natural("p.route_name")),
        DUTY("p.brigade"),
        TOWARDS("p.headsign"),
        DELAY("p.delay_seconds"),
        NEXT_STOP("p.next_stop_name");

        final String[] expressions;

        SampleSort(String... expressions) {
            this.expressions = expressions;
        }

        /** "delay,desc" → ORDER BY expressions; ties newest first. Unknown columns are rejected. */
        static String orderBy(String sort) {
            String[] parts = (sort == null || sort.isBlank() ? "time,desc" : sort).split(",");
            SampleSort column;
            try {
                column = valueOf(parts[0].strip().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("sort must be one of time, vehicle, line, duty, towards, delay, "
                        + "nextStop, optionally followed by ,asc or ,desc");
            }
            String direction = parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc") ? " DESC" : " ASC";
            StringBuilder order = new StringBuilder();
            for (String expression : column.expressions) {
                order.append(expression).append(direction).append(" NULLS LAST, ");
            }
            return order.append("p.observed_at DESC, p.vehicle_id").toString();
        }

        private static String[] natural(String column) {
            return new String[] {"(" + column + " !~ '^[0-9]+$')",
                    "CASE WHEN " + column + " ~ '^[0-9]{1,18}$' THEN " + column + "::bigint END", column};
        }
    }

    /**
     * Position reports in the window, newest first unless {@code sort} says otherwise (see {@link SampleSort}).
     *
     * @param excludeOutliers leave out reports more than {@link #OUTLIER_DELAY_SECONDS} late
     */
    public Page<PositionHistoryDto.Sample> samples(Instant from, Instant to, String route, String vehicle, int page,
                                                   int size, String sort, boolean excludeOutliers) {
        String orderBy = SampleSort.orderBy(sort);
        validate(from, to);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        StringBuilder where = new StringBuilder(" WHERE " + windowCondition("p"));
        List<Object> args = new ArrayList<>(window(from, to, null));
        if (route != null) {
            where.append(" AND p.route_name = ?");
            args.add(route);
        }
        if (vehicle != null) {
            where.append(" AND v.vehicle_number = ?");
            args.add(vehicle);
        }
        if (excludeOutliers) {
            where.append(" AND (p.delay_seconds IS NULL OR p.delay_seconds <= ").append(OUTLIER_DELAY_SECONDS)
                    .append(")");
        }
        String source = " FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + source + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<PositionHistoryDto.Sample> content = jdbc.query("""
                SELECT p.observed_at, p.vehicle_id, v.vehicle_number, p.route_name, p.brigade, p.headsign,
                       p.latitude, p.longitude, p.bearing, p.delay_seconds, p.next_stop_name""" + source + where
                        + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                (rs, i) -> new PositionHistoryDto.Sample(instant(rs, "observed_at"), rs.getLong("vehicle_id"),
                        rs.getString("vehicle_number"), rs.getString("route_name"), rs.getString("brigade"),
                        rs.getString("headsign"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        nullableInt(rs, "bearing"), nullableInt(rs, "delay_seconds"), rs.getString("next_stop_name")),
                pageArgs.toArray());
        return new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0 : total);
    }

    /** Arguments for the window condition, followed by the line when filtering by one. */
    private static List<Object> window(Instant from, Instant to, String route) {
        List<Object> args = new ArrayList<>(List.of(ts(from.minus(SLACK)), ts(to.plus(SLACK)), ts(from), ts(to)));
        if (route != null) {
            args.add(route);
        }
        return args;
    }

    static Duration stepFor(Instant from, Instant to) {
        Duration window = Duration.between(from, to);
        return STEPS.stream()
                .filter(step -> window.dividedBy(step) <= MAX_POINTS)
                .findFirst()
                .orElse(STEPS.get(STEPS.size() - 1));
    }

    static void validate(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required (ISO-8601, e.g. 2026-11-16T10:00:00Z)");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("The time window can be at most " + MAX_WINDOW.toDays() + " days");
        }
    }

    /** Numbers in natural order, then names with letters (N1, N2, …). */
    static final Comparator<String> NATURAL = (a, b) -> {
        boolean na = a.chars().allMatch(Character::isDigit);
        boolean nb = b.chars().allMatch(Character::isDigit);
        if (na && nb) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        return a.compareTo(b);
    };

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
