package com.transitanalytics.service;

import com.transitanalytics.buslanes.BusLaneMatcher;
import com.transitanalytics.buslanes.BusLaneStretches;
import com.transitanalytics.buslanes.BusLanes;
import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.dto.BusLaneDto;
import com.transitanalytics.geo.Geo;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static com.transitanalytics.service.TimetableMath.LINE_ORDER;
import static com.transitanalytics.service.TimetableMath.round;

/**
 * How fast buses go on bus lanes and elsewhere in the city, and how much delay they gain, from recorded positions.
 * Each pair of consecutive reports of a bus (a hop) counts where its middle is: on a bus lane (near one and heading
 * its way) or not. Speeds include stops and waits, as passengers ride them.
 */
@Service
public class BusLaneReportService {

    /** A report is computed at most this often per window. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Hops longer than this are gaps in the data. */
    static final int MAX_HOP_SECONDS = 90;
    /** Faster than this (m/s, 108 km/h) is a GPS jump. */
    static final double MAX_SPEED = 30;
    /** A bus that moved less than this (m) stood still; its heading is the one it reported. */
    static final double MOVED = 5;
    /** Hops with both ends this close (m) to a terminus are layovers, left out. */
    static final double TERMINUS_METERS = 150;
    /** A delay change larger than the hop's time plus this (s) is a new trip or a correction, not traffic. */
    static final int MAX_DELAY_JUMP = 120;
    /**
     * A bus not on a bus lane but this close (m) to one running the opposite way is on the same street in the
     * direction without a lane (a dual carriageway's lanes are 10-25 m apart).
     */
    static final double OPPOSITE_METERS = 30;
    /** Figures over less than this many km driven aren't given. */
    static final double MIN_KM = 0.5;

    /** A bucket's length. */
    static final Duration SLOT = Duration.ofMinutes(30);
    /**
     * A bucket is a half hour (its slot, on the city's clock, in its service day): it fills with the hops starting
     * in it (each at most {@link #MAX_HOP_SECONDS} long). A window takes the buckets starting in it.
     */
    static final ServiceDayCache.Rows<Bucket> ROWS = new ServiceDayCache.Rows<>(Bucket::start,
            b -> b.start().plus(SLOT), Duration.ofSeconds(MAX_HOP_SECONDS), Duration.ZERO, SLOT);

    /**
     * One service day's hops on one stretch, half-hour of the day and kind of day. {@code stretch}: the stretch's id
     * on its bus lane; {@code -2 - id} on the same street the other way, without a lane; -1 elsewhere.
     */
    record Bucket(LocalDate day, int stretch, int slot, boolean weekday, double meters, double seconds,
                  double delayMeters, double delaySeconds, Set<String> lines) {

        /** When its half hour starts (slots before the day's start hour are after midnight). */
        Instant start() {
            LocalTime time = LocalTime.ofSecondOfDay(slot * SLOT.toSeconds());
            LocalDate date = time.getHour() < ServiceDay.DAY_START_HOUR ? day.plusDays(1) : day;
            return date.atTime(time).atZone(ServiceDay.ZONE).toInstant();
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BusLanes lanes;
    private final Clock clock;
    private final RecentCache<String, BusLaneDto.Report> cache;
    private volatile ServiceDayCache<Bucket> days;
    private volatile int daysVersion;

    public BusLaneReportService(JdbcTemplate jdbc, TransactionTemplate transaction, BusLanes lanes, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.lanes = lanes;
        this.clock = clock;
        this.cache = new RecentCache<>(clock, CACHE_FOR, 16);
    }

    /** Reads the last week (the page's default) at startup, in the background. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("bus lanes", BusLaneReportService.class,
                () -> report(new ReportWindow(now.minus(Duration.ofDays(7)), now)));
    }

    public BusLaneDto.Report report(ReportWindow window) {
        BusLanes.Snapshot snapshot = lanes.current();
        return cache.get(window + "|" + snapshot.version(), () -> compute(window, snapshot));
    }

    BusLaneDto.Report compute(ReportWindow window, BusLanes.Snapshot snapshot) {
        List<Bucket> buckets = days(snapshot).get(window.from(), window.to());
        int recorded = (int) buckets.stream().map(Bucket::day).distinct().count();
        Predicate<Bucket> onLane = b -> b.stretch() >= 0;
        Predicate<Bucket> opposite = b -> b.stretch() <= -2;
        List<BusLaneDto.Hour> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            int h = hour;
            List<Bucket> at = buckets.stream().filter(b -> b.weekday() && b.slot() / 2 == h).toList();
            Totals lane = Totals.of(at, onLane);
            Totals other = Totals.of(at, onLane.negate());
            if (lane.meters + other.meters > 0) {
                hours.add(new BusLaneDto.Hour(hour, lane.speed(), other.speed(), lane.delayPerKm(),
                        other.delayPerKm(), round(lane.meters / 1000, 1), round(other.meters / 1000, 1)));
            }
        }
        Map<Integer, List<Bucket>> byStretch = new HashMap<>();
        Map<Integer, List<Bucket>> oppositeOf = new HashMap<>();
        for (Bucket b : buckets) {
            if (b.stretch() >= 0) {
                byStretch.computeIfAbsent(b.stretch(), k -> new ArrayList<>()).add(b);
            } else if (b.stretch() <= -2) {
                oppositeOf.computeIfAbsent(-2 - b.stretch(), k -> new ArrayList<>()).add(b);
            }
        }
        List<BusLaneDto.StretchFigures> stretches = new ArrayList<>();
        for (BusLaneStretches.Stretch s : snapshot.stretches().all()) {
            List<Bucket> mine = byStretch.get(s.id());
            if (mine == null) {
                continue;
            }
            Set<String> lines = new TreeSet<>(LINE_ORDER);
            mine.forEach(b -> lines.addAll(b.lines()));
            stretches.add(new BusLaneDto.StretchFigures(s.id(), s.name(), s.heading(), round(s.meters() / 1000, 2),
                    List.copyOf(lines), figures(mine, b -> true),
                    oppositeOf.containsKey(s.id()) ? figures(oppositeOf.get(s.id()), b -> true) : null));
        }
        stretches.sort(Comparator.comparingDouble((BusLaneDto.StretchFigures s) -> -s.figures().km()));
        return new BusLaneDto.Report(window.from(), window.to(), recorded, figures(buckets, onLane),
                figures(buckets, opposite), figures(buckets, onLane.negate()), hours, stretches);
    }

    private static BusLaneDto.Figures figures(List<Bucket> buckets, Predicate<Bucket> which) {
        Totals all = Totals.of(buckets, which);
        Totals peak = Totals.of(buckets, which.and(b -> b.weekday() && Periods.in(Periods.PEAK, b.slot() * 1800)));
        Totals offPeak = Totals.of(buckets,
                which.and(b -> b.weekday() && Periods.in(Periods.OFF_PEAK, b.slot() * 1800)));
        Double slowdown = peak.speed() == null || offPeak.speed() == null ? null
                : round((1 - peak.speed() / offPeak.speed()) * 100, 1);
        return new BusLaneDto.Figures(round(all.meters / 1000, 1), all.speed(), peak.speed(), offPeak.speed(),
                all.delayPerKm(), peak.delayPerKm(), offPeak.delayPerKm(), slowdown);
    }

    /** Sums over buckets. */
    private record Totals(double meters, double seconds, double delayMeters, double delaySeconds) {

        static Totals of(List<Bucket> buckets, Predicate<Bucket> which) {
            double m = 0, s = 0, dm = 0, ds = 0;
            for (Bucket b : buckets) {
                if (which.test(b)) {
                    m += b.meters();
                    s += b.seconds();
                    dm += b.delayMeters();
                    ds += b.delaySeconds();
                }
            }
            return new Totals(m, s, dm, ds);
        }

        /** km/h; null over too short a distance. */
        Double speed() {
            return meters < MIN_KM * 1000 || seconds <= 0 ? null : round(meters / seconds * 3.6, 1);
        }

        /** Seconds of delay gained per km; null over too short a distance. */
        Double delayPerKm() {
            return delayMeters < MIN_KM * 1000 ? null : round(delaySeconds / (delayMeters / 1000), 1);
        }
    }

    /** The day cache for these lanes; lanes refreshed: a new one, as hops match differently. */
    private ServiceDayCache<Bucket> days(BusLanes.Snapshot snapshot) {
        ServiceDayCache<Bucket> current = days;
        if (current == null || daysVersion != snapshot.version()) {
            synchronized (this) {
                if (days == null || daysVersion != snapshot.version()) {
                    days = new ServiceDayCache<>(clock, (from, to) -> query(from, to, snapshot), ROWS,
                            ServiceDayCache.positionCount(jdbc));
                    daysVersion = snapshot.version();
                }
                current = days;
            }
        }
        return current;
    }

    /** The hops in {@code [from, to)} (at most a service day), summed per stretch, half-hour and kind of day. */
    List<Bucket> query(Instant from, Instant to, BusLanes.Snapshot snapshot) {
        StandstillHotspots.Grid<double[]> termini =
                new StandstillHotspots.Grid<>(termini(), p -> p[0], p -> p[1], TERMINUS_METERS);
        CityCells city = new CityCells();
        BusLaneMatcher matcher = snapshot.matcher();
        BusLaneStretches stretches = snapshot.stretches();
        Map<List<Object>, double[]> sums = new HashMap<>();
        Map<List<Object>, Set<String>> lines = new HashMap<>();
        transaction.executeWithoutResult(status -> {
            JdbcTemplate streaming = new JdbcTemplate(jdbc.getDataSource());
            streaming.setFetchSize(10_000);
            // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
            streaming.query("""
                    WITH r AS (
                        SELECT route_name, observed_at AS t, latitude AS lat, longitude AS lon, bearing,
                               delay_seconds AS delay, lead(observed_at) OVER w AS nt, lead(latitude) OVER w AS nlat,
                               lead(longitude) OVER w AS nlon, lead(delay_seconds) OVER w AS ndelay,
                               lead(route_name) OVER w AS nline, headsign, lead(headsign) OVER w AS nheadsign,
                               next_stop_sequence AS seq, lead(next_stop_sequence) OVER w AS nseq
                        FROM position_sample
                        WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''
                        WINDOW w AS (PARTITION BY vehicle_id ORDER BY observed_at))
                    SELECT route_name, t, lat, lon, bearing, delay, date_part('epoch', nt - t) AS dt, nlat, nlon, ndelay,
                           headsign IS NOT DISTINCT FROM nheadsign
                               AND coalesce(nseq >= seq, true) AS same_trip
                    FROM r
                    WHERE nline = route_name AND nt - t BETWEEN interval '5 seconds' AND interval '%d seconds'
                      AND route_name IN (SELECT gtfs_id FROM route)""".formatted(MAX_HOP_SECONDS),
                    (RowCallbackHandler) rs -> hop(rs, termini, city, matcher, stretches, sums, lines),
                    Timestamp.from(from), Timestamp.from(to));
        });
        List<Bucket> buckets = new ArrayList<>();
        sums.forEach((key, s) -> buckets.add(new Bucket((LocalDate) key.get(0), (int) key.get(1), (int) key.get(2),
                (boolean) key.get(3), s[0], s[1], s[2], s[3], lines.getOrDefault(key, Set.of()))));
        return buckets;
    }

    private static void hop(ResultSet rs, StandstillHotspots.Grid<double[]> termini, CityCells city, BusLaneMatcher matcher,
                            BusLaneStretches stretches, Map<List<Object>, double[]> sums,
                            Map<List<Object>, Set<String>> lines) throws SQLException {
        double[] a = {rs.getDouble("lat"), rs.getDouble("lon")};
        double[] b = {rs.getDouble("nlat"), rs.getDouble("nlon")};
        double dt = rs.getDouble("dt");
        double meters = Geo.meters(a, b);
        double[] mid = {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
        if (meters > MAX_SPEED * dt || !city.contains(mid) || nearAny(termini, a) && nearAny(termini, b)) {
            return;
        }
        Integer bearing = (Integer) rs.getObject("bearing", Integer.class);
        double heading = meters >= MOVED ? Geo.heading(a, b) : bearing == null ? Double.NaN : bearing;
        BusLaneMatcher.Match match = Double.isNaN(heading) ? null : matcher.match(mid[0], mid[1], heading);
        int stretch = match == null ? -1 : stretches.of(match);
        // The other way only where the bus's own direction has no lane: with one a little further off (GPS), it's
        // neither.
        if (match == null && !Double.isNaN(heading)
                && matcher.match(mid[0], mid[1], heading, OPPOSITE_METERS) == null) {
            BusLaneMatcher.Match other = matcher.match(mid[0], mid[1], heading + 180, OPPOSITE_METERS);
            stretch = other == null ? -1 : -2 - stretches.of(other);
        }
        Instant t = rs.getTimestamp("t").toInstant();
        ZonedDateTime local = t.atZone(ServiceDay.ZONE);
        int slot = local.toLocalTime().toSecondOfDay() / 1800;
        boolean weekday = local.getDayOfWeek() != DayOfWeek.SATURDAY && local.getDayOfWeek() != DayOfWeek.SUNDAY;
        List<Object> key = List.of(ServiceDay.serviceDay(t), stretch, slot, weekday);
        double[] s = sums.computeIfAbsent(key, k -> new double[4]);
        s[0] += meters;
        s[1] += dt;
        Integer delay = (Integer) rs.getObject("delay", Integer.class);
        Integer next = (Integer) rs.getObject("ndelay", Integer.class);
        // Only within a trip: from one trip to the next the delay starts again.
        if (delay != null && next != null && rs.getBoolean("same_trip") && Math.abs(next - delay) <= dt + MAX_DELAY_JUMP) {
            s[2] += meters;
            s[3] += next - delay;
        }
        if (stretch >= 0) {
            lines.computeIfAbsent(key, k -> new HashSet<>()).add(rs.getString("route_name"));
        }
    }

    private static boolean nearAny(StandstillHotspots.Grid<double[]> termini, double[] p) {
        return !termini.nearIndexes(p[0], p[1], TERMINUS_METERS).isEmpty();
    }

    /** Stops where runs start or end. */
    private List<double[]> termini() {
        List<double[]> termini = new ArrayList<>();
        jdbc.query(StopPlaces.STOPS_WITH_TERMINALS, rs -> {
            if (rs.getBoolean("terminal")) {
                termini.add(new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")});
            }
        });
        return termini;
    }

    /** Whether places are in the city, by 100 m cells (each cell's middle tested once). */
    static final class CityCells {
        private static final double CELL = 100;
        private final Map<Long, Boolean> cells = new HashMap<>();

        boolean contains(double[] p) {
            double[] xy = Geo.xy(p[0], p[1]);
            long cx = (long) Math.floor(xy[0] / CELL);
            long cy = (long) Math.floor(xy[1] / CELL);
            return cells.computeIfAbsent((cx << 32) ^ (cy & 0xffffffffL), k -> CityBoundaries.inCity(
                    (cy + 0.5) * CELL / Geo.M_PER_DEG, (cx + 0.5) * CELL / (Geo.M_PER_DEG * Geo.COS_LAT)));
        }
    }
}
