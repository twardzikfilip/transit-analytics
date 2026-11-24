package com.transitanalytics.service;

import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.service.StandstillHotspots.Standstill;
import com.transitanalytics.service.StandstillHotspots.StopPoint;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.transitanalytics.service.TimetableMath.LINE_ORDER;
import static com.transitanalytics.service.TimetableMath.round;

/** Stop hotspots: where buses stand still away from stops, and how much time they lose there. */
@Service
public class HotspotService {

    /** A report is computed at most this often per window and line. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Two reports this close (in metres) are standing still; GPS wanders a few metres. */
    static final int STILL_METERS = 10;
    /** Two reports further apart than this aren't a pair: the bus may have moved in between. */
    static final int MAX_PAIR_SECONDS = 90;
    /** Longer standstills are layovers, breaks or breakdowns, not waiting in traffic. */
    static final Duration MAX_STANDSTILL = Duration.ofMinutes(5);

    /**
     * A standstill starts with its first pair of reports in one place; pairs of consecutive reports (at most
     * {@link #MAX_PAIR_SECONDS} apart) join it. Its query needs the report before it, to tell it starts there.
     */
    static final ServiceDayCache.Rows<Standstill> ROWS = new ServiceDayCache.Rows<>(Standstill::start,
            s -> s.start().plusMillis(Math.round(s.seconds() * 1000)), Duration.ofSeconds(MAX_PAIR_SECONDS),
            Duration.ofSeconds(2 * MAX_PAIR_SECONDS), Duration.ZERO);

    /** Raised whenever what is worked out for a day changes: stored days are out of date then. */
    private static final String LOGIC = "1";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ServiceDayCache<Standstill> standstills;
    private final RecentCache<String, HotspotDto.Report> cache;

    public HotspotService(JdbcTemplate jdbc, Clock clock, DaySummaries summaries) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.standstills = new ServiceDayCache<>(clock, this::query, ROWS, ServiceDayCache.positionCount(jdbc),
                summaries.store("hotspots", () -> LOGIC, Standstill.class));
        summaries.onOutdated("hotspots", standstills::forget);
        this.cache = new RecentCache<>(clock, CACHE_FOR, 16);
    }

    /** Reads the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("hotspots", HotspotService.class,
                () -> report(new ReportWindow(now.minus(Duration.ofDays(30)), now), null));
    }

    /**
     * The report for a window; asked again for the same window and line within {@link #CACHE_FOR}, the same one.
     *
     * @param line only this line's standstills, or null for all lines
     */
    public HotspotDto.Report report(ReportWindow window, String line) {
        return cache.get(window + "|" + line, () -> compute(window.from(), window.to(), line));
    }

    HotspotDto.Report compute(Instant from, Instant to, String line) {
        List<StopPoint> stops = stops();
        // Only lines in the timetable: other operators' stops and terminals aren't known, so their boarding and
        // layovers would look like waiting in traffic.
        Set<String> timetableLines = new HashSet<>(jdbc.queryForList("SELECT gtfs_id FROM route", String.class));
        List<Standstill> away = StandstillHotspots.awayFromStops(standstills.get(from, to).stream()
                .filter(s -> timetableLines.contains(s.line())).toList(), stops);
        List<String> lines = away.stream().map(Standstill::line)
                .collect(Collectors.toCollection(() -> new TreeSet<>(LINE_ORDER))).stream().toList();
        List<Standstill> chosen = line == null ? away : away.stream().filter(s -> s.line().equals(line)).toList();
        // Per day of recording, not of the window: a month's window may hold only a few recorded days.
        int days = (int) Math.max(1, away.stream().map(s -> ServiceDay.serviceDay(s.start())).distinct().count());
        List<HotspotDto.Hotspot> hotspots = StandstillHotspots.hotspots(chosen, stops, days);
        return new HotspotDto.Report(from, to, line, lines, days, chosen.size(),
                round(StandstillHotspots.seconds(chosen) / 3600, 1),
                round(hotspots.stream().mapToDouble(HotspotDto.Hotspot::hours).sum(), 1), hotspots);
    }

    /**
     * Standstills in {@code [from, to)}: runs of consecutive reports of a vehicle on one line, each within
     * {@link #STILL_METERS} of the next and at most 90 s apart (a longer gap is missing data), lasting up to
     * {@link #MAX_STANDSTILL}. Placed at the mean of the reports, heading as at the first one.
     */
    private List<Standstill> query(Instant from, Instant to) {
        // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
        return jdbc.query("""
                WITH r AS (
                    SELECT vehicle_id, observed_at AS t, latitude AS lat, longitude AS lon, route_name, bearing,
                           lead(observed_at) OVER w AS nt, lead(latitude) OVER w AS nlat,
                           lead(longitude) OVER w AS nlon, lead(route_name) OVER w AS nline
                    FROM position_sample
                    WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''
                    WINDOW w AS (PARTITION BY vehicle_id ORDER BY observed_at)),
                still AS (
                    -- Each pair of consecutive reports in one place; a pair not following on from the vehicle's
                    -- previous one starts a standstill.
                    SELECT *, CASE WHEN t = lag(nt) OVER (PARTITION BY vehicle_id ORDER BY t) THEN 0 ELSE 1 END AS starts
                    FROM r
                    WHERE nt - t BETWEEN interval '5 seconds' AND interval '%4$d seconds' AND nline = route_name
                      AND sqrt(power((nlon - lon) * cos(radians(lat)) * %1$s, 2)
                               + power((nlat - lat) * %1$s, 2)) < %2$d),
                numbered AS (
                    SELECT *, sum(starts) OVER (PARTITION BY vehicle_id ORDER BY t) AS n FROM still)
                SELECT vehicle_id, min(route_name) AS line, min(t) AS start,
                       date_part('epoch', max(nt) - min(t)) AS seconds,
                       avg((lat + nlat) / 2) AS lat, avg((lon + nlon) / 2) AS lon,
                       (array_agg(bearing ORDER BY t))[1] AS bearing
                FROM numbered
                GROUP BY vehicle_id, n
                HAVING max(nt) - min(t) <= interval '%3$d seconds'""".formatted(Geo.M_PER_DEG, STILL_METERS,
                        MAX_STANDSTILL.toSeconds(), MAX_PAIR_SECONDS),
                (rs, i) -> new Standstill(rs.getLong("vehicle_id"), rs.getString("line"),
                        rs.getTimestamp("start").toInstant(), rs.getDouble("seconds"), rs.getDouble("lat"),
                        rs.getDouble("lon"), (Integer) rs.getObject("bearing", Integer.class)),
                Timestamp.from(from), Timestamp.from(to));
    }

    /** Every stop of the timetable, with whether runs start or end there. */
    private List<StopPoint> stops() {
        return jdbc.query(StopPlaces.STOPS_WITH_TERMINALS,
                (rs, i) -> new StopPoint(rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getBoolean("terminal")));
    }
}
