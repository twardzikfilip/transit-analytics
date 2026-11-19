package com.transitanalytics.recording;

import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.service.TimetableMath;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.transitanalytics.service.ServiceDayCache;
import com.transitanalytics.service.WarmUp;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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
    static final Duration MAX_WINDOW = Duration.ofDays(366);
    /**
     * Bucket sizes to choose from; the smallest that keeps a chart at or under {@link #MAX_POINTS} points wins:
     * 1 minute up to 24 hours, 10 minutes for a week, 30 minutes for a month. Windows of up to {@link #FINE_WINDOW}
     * use {@link #FINE_STEP} instead.
     */
    static final List<Duration> STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3),
            Duration.ofHours(6), Duration.ofDays(1));
    static final int MAX_POINTS = 1500;
    /** One poll per point (positions are recorded every 10 seconds), for short windows. */
    static final Duration FINE_STEP = Duration.ofSeconds(10);
    /** Longest window shown poll by poll; longer ones would have gaps where older data was recorded every minute. */
    static final Duration FINE_WINDOW = Duration.ofHours(1);
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
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Map<String, ServiceDayCache<FleetPolls.Part>> fleetDays = new java.util.HashMap<>();

    public PositionHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Reads the last week's polls at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("position history", PositionHistoryService.class,
                () -> fleet(now.minus(Duration.ofDays(7)), now, null));
    }

    /** How far before the window reports are read, so vehicles running at its start are counted. */
    private static final Duration RUNNING_LOOKBACK = FleetPolls.REPORT_COVERS;
    /** Lines whose polls are kept (all lines, and the lines looked at most recently). */
    private static final int CACHED_ROUTES = 16;

    /**
     * Vehicles running and mean delay per bucket, plus totals, for polls in {@code [from, to)}. Worked out per service
     * day; days that are over are kept, so only today is read again (see {@link FleetPolls} for what "running" is).
     */
    public PositionHistoryDto.FleetSeries fleet(Instant from, Instant to, String route) {
        validate(from, to);
        Duration step = stepFor(from, to);
        List<FleetPolls.Part> parts = days(route).get(from, to);
        List<FleetPolls.Poll> polls = FleetPolls.between(parts, from, to);

        // Per poll first, then per bucket: vehicles = average per poll, delay = mean of reports. Buckets are aligned
        // to the epoch (UTC), so they stay the same when the window moves.
        long stepMs = step.toMillis();
        List<PositionHistoryDto.FleetPoint> points = new ArrayList<>();
        for (int i = 0; i < polls.size(); ) {
            long bucket = Math.floorDiv(polls.get(i).time().toEpochMilli(), stepMs) * stepMs;
            double vehicles = 0;
            long peak = 0;
            long reports = 0;
            long delaySum = 0;
            long delayCount = 0;
            int n = 0;
            for (; i < polls.size() && Math.floorDiv(polls.get(i).time().toEpochMilli(), stepMs) * stepMs == bucket;
                 i++, n++) {
                FleetPolls.Poll p = polls.get(i);
                vehicles += p.vehicles();
                peak = Math.max(peak, p.vehicles());
                reports += p.reports();
                delaySum += p.delaySum();
                delayCount += p.delayCount();
            }
            points.add(new PositionHistoryDto.FleetPoint(Instant.ofEpochMilli(bucket), vehicles / n, peak, reports,
                    delayCount == 0 ? null : (double) delaySum / delayCount));
        }

        long reports = polls.stream().mapToLong(FleetPolls.Poll::reports).sum();
        long delaySum = polls.stream().mapToLong(FleetPolls.Poll::delaySum).sum();
        long delayCount = polls.stream().mapToLong(FleetPolls.Poll::delayCount).sum();
        java.util.Set<Long> vehicles = new java.util.HashSet<>();
        parts.forEach(p -> vehicles.addAll(p.vehicles()));
        FleetPolls.Poll latest = polls.isEmpty() ? null : polls.get(polls.size() - 1);
        var summary = new PositionHistoryDto.Summary(reports, vehicles.size(),
                polls.stream().mapToLong(FleetPolls.Poll::vehicles).max().orElse(0),
                latest == null ? 0 : latest.vehicles(), latest == null ? null : latest.time(),
                delayCount == 0 ? null : (double) delaySum / delayCount,
                parts.stream().map(FleetPolls.Part::first).filter(java.util.Objects::nonNull).min(Instant::compareTo)
                        .orElse(null),
                parts.stream().map(FleetPolls.Part::last).filter(java.util.Objects::nonNull).max(Instant::compareTo)
                        .orElse(null));
        return new PositionHistoryDto.FleetSeries(from, to, step.toSeconds(), route, points, summary);
    }

    /** The polls of a line (or all lines, null), by service day. */
    private ServiceDayCache<FleetPolls.Part> days(String route) {
        String key = route == null ? "" : route;
        synchronized (fleetDays) {
            if (!fleetDays.containsKey(key) && fleetDays.size() >= CACHED_ROUTES) {
                fleetDays.keySet().removeIf(k -> !k.isEmpty());
            }
            return fleetDays.computeIfAbsent(key,
                    k -> new ServiceDayCache<>(clock, (from, to) -> List.of(readPolls(from, to, route))));
        }
    }

    /** The polls in {@code [from, to)}, from the reports since a minute before (read in one pass, streamed). */
    FleetPolls.Part readPolls(Instant from, Instant to, String route) {
        String routeFilter = route == null ? "" : " AND route_name = ?";
        List<FleetPolls.Report> reports = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            JdbcTemplate streaming = new JdbcTemplate(jdbc.getDataSource());
            streaming.setFetchSize(10_000);
            streaming.query("""
                    SELECT coalesce(fetched_at, observed_at) AS poll, observed_at, vehicle_id, delay_seconds
                    FROM position_sample
                    WHERE %s%s""".formatted(WINDOW, routeFilter),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> reports.add(new FleetPolls.Report(
                            rs.getTimestamp("poll").getTime(), rs.getTimestamp("observed_at").getTime(),
                            rs.getLong("vehicle_id"), (Integer) rs.getObject("delay_seconds", Integer.class))),
                    window(from.minus(RUNNING_LOOKBACK), to, route).toArray());
        });
        return FleetPolls.polls(reports, from);
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
    static final Duration MAX_REPLAY_WINDOW = Duration.ofDays(92);
    /** Frame steps to choose from; the smallest keeping a replay at or under {@link #MAX_FRAMES} frames wins. */
    static final List<Duration> REPLAY_STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6));
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
        Map<String, Integer> brigadeIndex = new java.util.LinkedHashMap<>();
        java.util.TreeMap<Instant, List<List<Object>>> frames = new java.util.TreeMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (bucket, p.vehicle_id)
                       time_bucket(make_interval(secs => ?::integer), coalesce(p.fetched_at, p.observed_at)) AS bucket,
                       v.vehicle_number, p.route_name, p.brigade, p.latitude, p.longitude, p.delay_seconds
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE %s%s
                ORDER BY bucket, p.vehicle_id, p.observed_at DESC""".formatted(windowCondition("p"), routeFilter),
                rs -> {
                    int vehicle = vehicleIndex.computeIfAbsent(rs.getString("vehicle_number"), k -> vehicleIndex.size());
                    String route = rs.getString("route_name");
                    Integer line = route == null ? null : lineIndex.computeIfAbsent(route, k -> lineIndex.size());
                    String brigade = rs.getString("brigade");
                    Integer duty = brigade == null ? null
                            : brigadeIndex.computeIfAbsent(brigade, k -> brigadeIndex.size());
                    List<Object> row = new ArrayList<>(6);
                    row.add(vehicle);
                    row.add(round(rs.getDouble("latitude"), 6));
                    row.add(round(rs.getDouble("longitude"), 6));
                    row.add(nullableInt(rs, "delay_seconds"));
                    row.add(line);
                    row.add(duty);
                    frames.computeIfAbsent(instant(rs, "bucket"), k -> new ArrayList<>()).add(row);
                },
                args.toArray());

        return new PositionHistoryDto.Replay(from, to, step.toSeconds(), lineFilter,
                List.copyOf(vehicleIndex.keySet()), List.copyOf(lineIndex.keySet()), List.copyOf(brigadeIndex.keySet()),
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
                WHERE %s AND route_name IS NOT NULL
                GROUP BY route_name""".formatted(WINDOW),
                (rs, i) -> new PositionHistoryDto.RouteActivity(rs.getString("route_name"), rs.getLong("samples")),
                window(from, to, null).toArray());
        return routes.stream().sorted(Comparator.comparing(PositionHistoryDto.RouteActivity::route, TimetableMath.LINE_ORDER)).toList();
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
        String source = "FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id";
        Long total = jdbc.queryForObject("SELECT count(*) %s%s".formatted(source, where), Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<PositionHistoryDto.Sample> content = jdbc.query("""
                SELECT p.observed_at, p.vehicle_id, v.vehicle_number, p.route_name, p.brigade, p.headsign,
                       p.latitude, p.longitude, p.bearing, p.delay_seconds, p.next_stop_name
                %s%s
                ORDER BY %s LIMIT ? OFFSET ?""".formatted(source, where, orderBy),
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
        if (window.compareTo(FINE_WINDOW) <= 0) {
            return FINE_STEP;
        }
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
