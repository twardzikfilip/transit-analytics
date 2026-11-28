package com.transitanalytics.recording;

import com.transitanalytics.common.RecentCache;
import com.transitanalytics.common.ServiceDayCache;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.timetable.TimetableMath;
import com.transitanalytics.zbiorkom.BialystokFeed;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads the recorded position time series ({@code position_sample}), for one operator's routes or all of them. */
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
    /**
     * Where the delay bands start, in whole minutes: under 2 minutes late (or early) is on time, then 2–5, 5–10, and 10
     * minutes or more.
     */
    static final List<Integer> DELAY_BAND_MINUTES = List.of(2, 5, 10);

    /**
     * Rows of polls made in [from, to). A poll is identified by {@code fetched_at}; imported files without one use the
     * report's own time. The extra condition on {@code observed_at} (the hypertable's time column, with slack for
     * clock differences) lets TimescaleDB skip chunks outside the window. Takes the arguments of {@link #window}.
     *
     * @param agency whether to keep one operator's vehicles only (its name is then an argument)
     */
    private static String windowCondition(String alias, boolean agency) {
        String p = alias.isEmpty() ? "" : alias + ".";
        return "%1$sobserved_at >= ? AND %1$sobserved_at < ?".formatted(p)
                + " AND coalesce(%1$sfetched_at, %1$sobserved_at) >= ? AND coalesce(%1$sfetched_at, %1$sobserved_at) < ?"
                .formatted(p)
                // Every route in the feed is run by a single operator, so the vehicle's operator picks its routes.
                + (agency ? " AND %svehicle_id IN (SELECT id FROM vehicle WHERE agency = ?)".formatted(p) : "");
    }

    private static final Duration SLACK = Duration.ofMinutes(15);
    /** How long the route and delay counts of a window are kept (windows end on the minute, so a reload hits). */
    static final Duration COUNTS_KEPT_FOR = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Map<String, ServiceDayCache<FleetPolls.Part>> fleetDays = new HashMap<>();
    /** The route and delay filters' answers, kept briefly: they count every report in the window. */
    private final RecentCache<String, List<PositionHistoryDto.RouteActivity>> routes;
    private final RecentCache<String, List<PositionHistoryDto.DelayBand>> delays;

    public PositionHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.routes = new RecentCache<>(clock, COUNTS_KEPT_FOR, 16);
        this.delays = new RecentCache<>(clock, COUNTS_KEPT_FOR, 32);
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Reads the last week's polls at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("position history", PositionHistoryService.class,
                () -> fleet(now.minus(Duration.ofDays(7)), now, null, BialystokFeed.CITY_AGENCY));
    }

    /** How far before the window reports are read, so vehicles running at its start are counted. */
    private static final Duration RUNNING_LOOKBACK = FleetPolls.REPORT_COVERS;
    /** Routes whose polls are kept (all routes, and the routes looked at most recently), per operator choice. */
    private static final int CACHED_ROUTES = 16;

    /**
     * Vehicles running and mean delay per bucket, plus totals, for polls in {@code [from, to)}. Worked out per service
     * day; days that are over are kept, so only today is read again (see {@link FleetPolls} for what "running" is).
     *
     * @param route  a route, or null for all
     * @param agency an operator, or null for all
     */
    public PositionHistoryDto.FleetSeries fleet(Instant from, Instant to, String route, String agency) {
        validate(from, to);
        Duration step = stepFor(from, to);
        List<FleetPolls.Part> parts = days(route, agency).get(from, to);
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
        Set<Long> vehicles = new HashSet<>();
        parts.forEach(p -> vehicles.addAll(p.vehicles()));
        FleetPolls.Poll latest = polls.isEmpty() ? null : polls.get(polls.size() - 1);
        var summary = new PositionHistoryDto.Summary(reports, vehicles.size(),
                polls.stream().mapToLong(FleetPolls.Poll::vehicles).max().orElse(0),
                latest == null ? 0 : latest.vehicles(), latest == null ? null : latest.time(),
                delayCount == 0 ? null : (double) delaySum / delayCount,
                parts.stream().map(FleetPolls.Part::first).filter(Objects::nonNull).min(Instant::compareTo)
                        .orElse(null),
                parts.stream().map(FleetPolls.Part::last).filter(Objects::nonNull).max(Instant::compareTo)
                        .orElse(null));
        return new PositionHistoryDto.FleetSeries(from, to, step.toSeconds(), route, points, summary);
    }

    /** The polls of a route (or all routes, null) of an operator (or all, null), by service day. */
    private ServiceDayCache<FleetPolls.Part> days(String route, String agency) {
        String key = (agency == null ? "*" : agency) + "|" + (route == null ? "" : route);
        synchronized (fleetDays) {
            if (!fleetDays.containsKey(key) && fleetDays.size() >= CACHED_ROUTES) {
                fleetDays.keySet().removeIf(k -> !k.endsWith("|"));
            }
            return fleetDays.computeIfAbsent(key,
                    k -> new ServiceDayCache<>(clock, (from, to) -> List.of(readPolls(from, to, route, agency))));
        }
    }

    /** The polls in {@code [from, to)}, from the reports since a minute before (read in one pass, streamed). */
    FleetPolls.Part readPolls(Instant from, Instant to, String route, String agency) {
        String routeFilter = route == null ? "" : " AND route_name = ?";
        List<FleetPolls.Report> reports = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            JdbcTemplate streaming = new JdbcTemplate(jdbc.getDataSource());
            streaming.setFetchSize(10_000);
            streaming.query("""
                    SELECT coalesce(fetched_at, observed_at) AS poll, observed_at, vehicle_id, delay_seconds
                    FROM position_sample
                    WHERE %s%s""".formatted(windowCondition("", agency != null), routeFilter),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> reports.add(new FleetPolls.Report(
                            rs.getTimestamp("poll").getTime(), rs.getTimestamp("observed_at").getTime(),
                            rs.getLong("vehicle_id"), (Integer) rs.getObject("delay_seconds", Integer.class))),
                    window(from.minus(RUNNING_LOOKBACK), to, agency, route).toArray());
        });
        return FleetPolls.polls(reports, from);
    }

    /**
     * How many vehicles and reports fall in each delay band (see {@link #DELAY_BAND_MINUTES}): on time (under 2
     * minutes late, or early), 2–5, 5–10, and 10 minutes or more. Reports without a delay are left out.
     *
     * @param route  a route, or null for all
     * @param agency an operator, or null for all
     */
    public List<PositionHistoryDto.DelayBand> delays(Instant from, Instant to, String route, String agency) {
        validate(from, to);
        return delays.get(from + "|" + to + "|" + route + "|" + agency, () -> countDelays(from, to, route, agency));
    }

    /** Forgets the kept route and delay counts (for tests, which record new reports in the same window). */
    void forgetCounts() {
        routes.clear();
        delays.clear();
    }

    private List<PositionHistoryDto.DelayBand> countDelays(Instant from, Instant to, String route, String agency) {
        String routeFilter = route == null ? "" : " AND route_name = ?";
        List<Integer> starts = DELAY_BAND_MINUTES;
        StringBuilder band = new StringBuilder("CASE");
        for (int i = starts.size() - 1; i >= 0; i--) {
            band.append(" WHEN delay_seconds >= ").append(starts.get(i) * 60).append(" THEN ").append(i + 1);
        }
        band.append(" ELSE 0 END");
        long[][] counts = new long[starts.size() + 1][2];
        jdbc.query("""
                SELECT %s AS band, count(DISTINCT vehicle_id) AS vehicles, count(*) AS reports
                FROM position_sample
                WHERE %s AND delay_seconds IS NOT NULL%s
                GROUP BY band""".formatted(band, windowCondition("", agency != null), routeFilter),
                rs -> {
                    int i = rs.getInt("band");
                    counts[i][0] = rs.getLong("vehicles");
                    counts[i][1] = rs.getLong("reports");
                },
                window(from, to, agency, route).toArray());
        List<PositionHistoryDto.DelayBand> bands = new ArrayList<>();
        for (int i = 0; i <= starts.size(); i++) {
            bands.add(new PositionHistoryDto.DelayBand(i == 0 ? null : starts.get(i - 1),
                    i == starts.size() ? null : starts.get(i), counts[i][0], counts[i][1]));
        }
        return bands;
    }

    /** Operators with vehicles on the feed, the city operator first, then by name. */
    public List<String> agencies() {
        return jdbc.queryForList("""
                SELECT DISTINCT agency FROM vehicle WHERE agency IS NOT NULL AND agency <> ''""", String.class)
                .stream().sorted(Comparator.comparing((String a) -> !a.equals(BialystokFeed.CITY_AGENCY))
                        .thenComparing(Comparator.naturalOrder())).toList();
    }

    /** Routes of an operator (or all, null) recorded in the window, in natural route order (2, 10, 100, N1). */
    public List<PositionHistoryDto.RouteActivity> routes(Instant from, Instant to, String agency) {
        validate(from, to);
        return routes.get(from + "|" + to + "|" + agency, () -> countRoutes(from, to, agency));
    }

    private List<PositionHistoryDto.RouteActivity> countRoutes(Instant from, Instant to, String agency) {
        // The mean delay leaves out outliers (feed errors), as the route pages do.
        List<PositionHistoryDto.RouteActivity> routes = jdbc.query("""
                SELECT route_name, count(*) AS samples,
                       avg(delay_seconds) FILTER (WHERE abs(delay_seconds) <= %2$d) AS mean_delay,
                       count(delay_seconds) FILTER (WHERE abs(delay_seconds) <= %2$d) AS delay_samples
                FROM position_sample
                WHERE %1$s AND route_name IS NOT NULL
                GROUP BY route_name""".formatted(windowCondition("", agency != null), DelayOutliers.THRESHOLD_SECONDS),
                (rs, i) -> new PositionHistoryDto.RouteActivity(rs.getString("route_name"), rs.getLong("samples"),
                        rs.getObject("mean_delay") == null ? null : Math.round(rs.getDouble("mean_delay")),
                        rs.getLong("delay_samples")),
                window(from, to, agency, null).toArray());
        return routes.stream().sorted(Comparator.comparing(PositionHistoryDto.RouteActivity::route, TimetableMath.ROUTE_ORDER)).toList();
    }

    /** Arguments for the window condition, then the operator and the route when filtering by them. */
    private static List<Object> window(Instant from, Instant to, String agency, String route) {
        List<Object> args = new ArrayList<>(List.of(ts(from.minus(SLACK)), ts(to.plus(SLACK)), ts(from), ts(to)));
        if (agency != null) {
            args.add(agency);
        }
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
}
