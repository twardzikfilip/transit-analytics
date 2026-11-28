package com.transitanalytics.data;

import com.transitanalytics.common.ServiceDayCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Recorded positions as {@link Report}s, each with the brigade it belongs to as the timetable names it. The running
 * service day's are kept in memory ({@link RecentReports}); older ones are read from the database.
 */
@Component
public class Reports {

    /**
     * SQL: recorded positions ({@code position_sample p}) with their bus ({@code vehicle v}) and the brigade their live
     * number belongs to, if learnt ({@code recorded_brigade_line lb}).
     */
    public static final String REPORTS_FROM = """
            position_sample p JOIN vehicle v ON v.id = p.vehicle_id
            LEFT JOIN recorded_brigade_line lb
                   ON lb.agency = v.agency AND lb.line = p.route_name AND lb.live_brigade = p.brigade""";

    /** SQL on {@link #REPORTS_FROM}: the brigade of a report, as the timetable names it (BKM's live as they are). */
    public static final String BRIGADE_SQL = "coalesce(lb.brigade, p.brigade)";

    /**
     * Two consecutive reports of a bus further apart than this aren't a pair: the time between is missing data. The
     * features that need a closer pair (hotspots, bus lanes) ask for it on top.
     */
    public static final Duration PAIR_GAP = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate streaming;
    private final TransactionTemplate transaction;
    private final RecentReports recent;

    public Reports(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        // A large result is only read in pieces (a cursor) inside a transaction.
        this.streaming = new JdbcTemplate(jdbc.getDataSource());
        this.streaming.setFetchSize(10_000);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.transaction.setReadOnly(true);
        this.recent = new RecentReports(clock, this::read, this::readMinutes, ServiceDayCache.positionCount(jdbc));
    }

    /** Reports on the timetable's routes in {@code [from, to)}, by vehicle and time. */
    public List<Report> of(Instant from, Instant to) {
        return recent.read(from, to, () -> recent.reports(from, to)).orElseGet(() -> read(from, to));
    }

    /**
     * As {@link #of(Instant, Instant)}, of the buses that ran any of some brigades then (any of their reports, also
     * on other brigades).
     */
    public List<Report> ofBrigades(Instant from, Instant to, Collection<String> brigades) {
        return recent.read(from, to, () -> recent.reports(from, to, brigades)).orElseGet(() -> read(BRIGADE_SQL, """
                p.vehicle_id IN (SELECT p.vehicle_id FROM %s
                                 WHERE p.observed_at >= ? AND p.observed_at < ? AND %s = ANY (?))""".formatted(
                        REPORTS_FROM, BRIGADE_SQL),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                brigades.toArray(String[]::new)));
    }

    /** One operator's reports in {@code [from, to)} with the brigades as the live feed names them, by vehicle and time. */
    public List<Report> live(Instant from, Instant to, String agency) {
        return read("p.brigade", "v.agency = ?", Timestamp.from(from), Timestamp.from(to), agency);
    }

    /** The minutes (epoch / 60) with any report in {@code [from, to)}: when recording went on. */
    public Set<Long> minutes(Instant from, Instant to) {
        return recent.read(from, to, () -> recent.minutes(from, to)).orElseGet(() -> readMinutes(from, to));
    }

    /** The brigades a bus (by number) reported in {@code [from, to)}, as the timetable names them. */
    public Set<String> brigadesOf(String bus, Instant from, Instant to) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT %s FROM %s
                WHERE p.observed_at >= ? AND p.observed_at < ? AND lower(v.vehicle_number) = lower(?)
                  AND p.brigade IS NOT NULL""".formatted(BRIGADE_SQL, REPORTS_FROM),
                String.class, Timestamp.from(from), Timestamp.from(to), bus));
    }

    /**
     * Whether two reports of a bus, the second right after the first, are a pair: on the same route of the timetable,
     * at most {@link #PAIR_GAP} apart. As {@link #pairs} pairs them.
     */
    public static boolean paired(Report a, Report b) {
        double seconds = Duration.between(a.time(), b.time()).toMillis() / 1000.0;
        return a.vehicleId() == b.vehicleId() && a.route() != null && a.route().equals(b.route())
                && seconds > 0 && seconds <= PAIR_GAP.toSeconds();
    }

    /**
     * Every pair of consecutive reports in {@code [from, to)} (each bus's reports in time order) on the same route of
     * the timetable, at most {@link #PAIR_GAP} apart, by bus and time. Read in pieces and handed on one by one: a
     * day has about a million.
     */
    public void pairs(Instant from, Instant to, Consumer<ReportPair> each) {
        transaction.executeWithoutResult(status -> streaming.query("""
                WITH r AS (
                    SELECT vehicle_id, observed_at AS t, route_name, brigade, headsign, delay_seconds,
                           next_stop_sequence, next_stop_name, latitude, longitude, bearing,
                           lead(observed_at) OVER w AS n_t, lead(route_name) OVER w AS n_route,
                           lead(brigade) OVER w AS n_brigade, lead(headsign) OVER w AS n_headsign,
                           lead(delay_seconds) OVER w AS n_delay, lead(next_stop_sequence) OVER w AS n_seq,
                           lead(latitude) OVER w AS n_lat, lead(longitude) OVER w AS n_lon
                    FROM position_sample
                    WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''
                    WINDOW w AS (PARTITION BY vehicle_id ORDER BY observed_at))
                SELECT r.*, v.vehicle_number, coalesce(lb.brigade, r.brigade) AS a_brigade,
                       coalesce(nlb.brigade, r.n_brigade) AS b_brigade
                FROM r JOIN vehicle v ON v.id = r.vehicle_id
                     LEFT JOIN recorded_brigade_line lb
                            ON lb.agency = v.agency AND lb.line = r.route_name AND lb.live_brigade = r.brigade
                     LEFT JOIN recorded_brigade_line nlb
                            ON nlb.agency = v.agency AND nlb.line = r.route_name AND nlb.live_brigade = r.n_brigade
                WHERE r.n_route = r.route_name AND r.n_t > r.t AND r.n_t - r.t <= interval '%d seconds'
                  AND r.route_name IN (SELECT gtfs_id FROM route)
                ORDER BY r.vehicle_id, r.t""".formatted(PAIR_GAP.toSeconds()),
                (RowCallbackHandler) rs -> {
                    long vehicle = rs.getLong("vehicle_id");
                    String number = rs.getString("vehicle_number");
                    String route = rs.getString("route_name");
                    Report a = new Report(vehicle, number, rs.getTimestamp("t").toInstant(), route,
                            rs.getString("a_brigade"), rs.getString("headsign"),
                            rs.getObject("delay_seconds", Integer.class), rs.getObject("next_stop_sequence", Integer.class),
                            rs.getDouble("latitude"), rs.getDouble("longitude"));
                    Report b = new Report(vehicle, number, rs.getTimestamp("n_t").toInstant(), route,
                            rs.getString("b_brigade"), rs.getString("n_headsign"),
                            rs.getObject("n_delay", Integer.class), rs.getObject("n_seq", Integer.class),
                            rs.getDouble("n_lat"), rs.getDouble("n_lon"));
                    each.accept(new ReportPair(a, b, rs.getObject("bearing", Integer.class),
                            rs.getString("next_stop_name")));
                },
                Timestamp.from(from), Timestamp.from(to)));
    }

    /** The running day's reports kept in memory, for reading in pieces. */
    public RecentReports recent() {
        return recent;
    }

    /** Drops the reports kept in memory: the timetable or the learnt brigades changed, so they read differently. */
    public void forget() {
        recent.forget();
    }

    private List<Report> read(Instant from, Instant to) {
        return read(BRIGADE_SQL, "TRUE", Timestamp.from(from), Timestamp.from(to));
    }

    private Set<Long> readMinutes(Instant from, Instant to) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT (extract(epoch FROM observed_at) / 60)::bigint FROM position_sample
                WHERE observed_at >= ? AND observed_at < ?""", Long.class, Timestamp.from(from), Timestamp.from(to)));
    }

    /**
     * Reports on the timetable's routes in a time window (the first two arguments), meeting a condition, by vehicle
     * and time.
     *
     * @param brigade SQL of a report's brigade, on {@link #REPORTS_FROM}
     */
    private List<Report> read(String brigade, String condition, Object... args) {
        // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
        return jdbc.query("""
                SELECT p.vehicle_id, v.vehicle_number, p.observed_at, p.route_name, %s AS brigade, p.headsign,
                       p.delay_seconds, p.next_stop_sequence, p.latitude, p.longitude
                FROM %s
                WHERE p.observed_at >= ? AND p.observed_at < ? AND p.route_name IN (SELECT gtfs_id FROM route)
                  AND %s
                ORDER BY p.vehicle_id, p.observed_at""".formatted(brigade, REPORTS_FROM, condition),
                (rs, i) -> new Report(rs.getLong("vehicle_id"), rs.getString("vehicle_number"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getString("route_name"),
                        rs.getString("brigade"), rs.getString("headsign"),
                        (Integer) rs.getObject("delay_seconds"), rs.getObject("next_stop_sequence", Integer.class),
                        rs.getDouble("latitude"), rs.getDouble("longitude")),
                args);
    }
}
