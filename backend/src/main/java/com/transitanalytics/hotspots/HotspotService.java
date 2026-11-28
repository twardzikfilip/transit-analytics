package com.transitanalytics.hotspots;

import com.transitanalytics.data.Report;
import com.transitanalytics.data.ReportPair;
import com.transitanalytics.data.Reports;
import com.transitanalytics.common.DaySummaries;
import com.transitanalytics.common.RecentCache;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.common.ServiceDayCache;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.hotspots.StandstillHotspots.Standstill;
import com.transitanalytics.hotspots.StandstillHotspots.StopPoint;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.timetable.StopPlaces;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.transitanalytics.timetable.TimetableMath.ROUTE_ORDER;
import static com.transitanalytics.timetable.TimetableMath.round;

/** Stop hotspots: where buses stand still away from stops, and how much time they lose there. */
@Service
public class HotspotService {

    /** A report is computed at most this often per window and route. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Two reports this close (in metres) are standing still; GPS wanders a few metres. */
    static final int STILL_METERS = 10;
    /** Pairs of reports further apart than this don't count: the bus may have moved in between. */
    static final int MAX_PAIR_SECONDS = 90;
    /** …nor closer than this (the feed repeating a report). */
    static final int MIN_PAIR_SECONDS = 5;
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
    private final Reports reports;
    private final Clock clock;
    private final ServiceDayCache<Standstill> standstills;
    private final RecentCache<String, HotspotDto.Report> cache;

    public HotspotService(JdbcTemplate jdbc, Reports reports, Clock clock, DaySummaries summaries) {
        this.jdbc = jdbc;
        this.reports = reports;
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
     * The report for a window; asked again for the same window and route within {@link #CACHE_FOR}, the same one.
     *
     * @param route only this route's standstills, or null for all routes
     */
    public HotspotDto.Report report(ReportWindow window, String route) {
        return cache.get(window + "|" + route, () -> compute(window.from(), window.to(), route));
    }

    HotspotDto.Report compute(Instant from, Instant to, String route) {
        List<StopPoint> stops = stops();
        // Only routes in the timetable: other operators' stops and terminals aren't known, so their boarding and
        // layovers would look like waiting in traffic.
        Set<String> timetableRoutes = new HashSet<>(jdbc.queryForList("SELECT gtfs_id FROM route", String.class));
        List<Standstill> away = StandstillHotspots.awayFromStops(standstills.get(from, to).stream()
                .filter(s -> timetableRoutes.contains(s.route())).toList(), stops);
        List<String> routes = away.stream().map(Standstill::route)
                .collect(Collectors.toCollection(() -> new TreeSet<>(ROUTE_ORDER))).stream().toList();
        List<Standstill> chosen = route == null ? away : away.stream().filter(s -> s.route().equals(route)).toList();
        // Per day of recording, not of the window: a month's window may hold only a few recorded days.
        int days = (int) Math.max(1, away.stream().map(s -> ServiceDay.serviceDay(s.start())).distinct().count());
        List<HotspotDto.Hotspot> hotspots = StandstillHotspots.hotspots(chosen, stops, days);
        return new HotspotDto.Report(from, to, route, routes, days, chosen.size(),
                round(StandstillHotspots.seconds(chosen) / 3600, 1),
                round(hotspots.stream().mapToDouble(HotspotDto.Hotspot::hours).sum(), 1), hotspots);
    }

    /**
     * Standstills in {@code [from, to)}: runs of pairs of a bus's reports ({@link Reports#pairs}) in one place (within
     * {@link #STILL_METERS}, {@link #MIN_PAIR_SECONDS} to {@link #MAX_PAIR_SECONDS} apart), each starting where the one
     * before ended, lasting up to {@link #MAX_STANDSTILL}. Placed at the mean of the pairs' midpoints, heading as at
     * the first one.
     */
    private List<Standstill> query(Instant from, Instant to) {
        List<Standstill> standstills = new ArrayList<>();
        List<ReportPair> run = new ArrayList<>();
        reports.pairs(from, to, pair -> {
            if (!still(pair)) {
                return;
            }
            ReportPair last = run.isEmpty() ? null : run.get(run.size() - 1);
            if (last != null && !(last.to().vehicleId() == pair.from().vehicleId()
                    && last.to().time().equals(pair.from().time()))) {
                standstill(run).ifPresent(standstills::add);
                run.clear();
            }
            run.add(pair);
        });
        standstill(run).ifPresent(standstills::add);
        return standstills;
    }

    /** Whether a pair of reports is the bus standing in one place. */
    static boolean still(ReportPair pair) {
        double seconds = pair.seconds();
        Report a = pair.from();
        Report b = pair.to();
        double metres = Math.sqrt(Math.pow((b.lon() - a.lon()) * Math.cos(Math.toRadians(a.lat())) * Geo.M_PER_DEG, 2)
                + Math.pow((b.lat() - a.lat()) * Geo.M_PER_DEG, 2));
        return seconds >= MIN_PAIR_SECONDS && seconds <= MAX_PAIR_SECONDS && metres < STILL_METERS;
    }

    /** A run of still pairs as a standstill; none when it is empty or too long. */
    static Optional<Standstill> standstill(List<ReportPair> run) {
        if (run.isEmpty()) {
            return Optional.empty();
        }
        ReportPair first = run.get(0);
        Instant start = first.from().time();
        double seconds = Duration.between(start, run.get(run.size() - 1).to().time()).toMillis() / 1000.0;
        if (seconds > MAX_STANDSTILL.toSeconds()) {
            return Optional.empty();
        }
        double lat = run.stream().mapToDouble(p -> (p.from().lat() + p.to().lat()) / 2).average().orElseThrow();
        double lon = run.stream().mapToDouble(p -> (p.from().lon() + p.to().lon()) / 2).average().orElseThrow();
        return Optional.of(new Standstill(first.from().vehicleId(), first.from().route(), start, seconds, lat, lon,
                first.bearing()));
    }

    /** Every stop of the timetable, with whether runs start or end there. */
    private List<StopPoint> stops() {
        return jdbc.query(StopPlaces.STOPS_WITH_TERMINALS,
                (rs, i) -> new StopPoint(rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getBoolean("terminal")));
    }
}
