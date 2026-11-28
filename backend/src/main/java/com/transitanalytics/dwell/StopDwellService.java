package com.transitanalytics.dwell;

import com.transitanalytics.common.DaySummaries;
import com.transitanalytics.common.RecentCache;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.common.ServiceDayCache;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.data.Reports;
import com.transitanalytics.data.Shapes;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.dwell.StopDwells.Hour;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.transitanalytics.timetable.TimetableMath.round;

/**
 * The stops where buses dwell longest: each visit of a bus to a stop along its route measured as a trip's stop is
 * (see {@link StopDwells}), worked out per service day from the pairs of reports and stored, then averaged over a
 * window.
 */
@Service
public class StopDwellService {

    /** A window's answer is worked out at most this often. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Stops with fewer visits in the window aren't ranked: a stop seen once doesn't lead. */
    static final int MIN_VISITS = 20;
    /** How many stops are listed. */
    static final int TOP = 5;
    /** Raised whenever what is worked out for a day changes: stored days are out of date then. */
    private static final String LOGIC = "1";

    /**
     * An hour's row takes the visits starting in it; a visit goes on at most {@link StopDwells#MAX_DWELL} (and a pair's
     * gap) after, and its first pair needs the report before it.
     */
    static final ServiceDayCache.Rows<Hour> ROWS = new ServiceDayCache.Rows<>(Hour::hour,
            h -> h.hour().plus(Duration.ofHours(1)), StopDwells.MAX_DWELL.plus(Reports.PAIR_GAP),
            StopDwells.MAX_DWELL.plus(Reports.PAIR_GAP), Duration.ofHours(1));

    private record RouteStops(String version, Map<String, List<Timetables.Stop>> stops) {
    }

    private final Reports reports;
    private final Timetables timetables;
    private final Shapes shapes;
    private final Clock clock;
    private final ServiceDayCache<Hour> hours;
    private final RecentCache<String, List<PositionHistoryDto.StopDwell>> cache;
    private volatile RouteStops routeStops;

    public StopDwellService(Reports reports, Timetables timetables, Shapes shapes, JdbcTemplate jdbc, Clock clock,
                            DaySummaries summaries) {
        this.reports = reports;
        this.timetables = timetables;
        this.shapes = shapes;
        this.clock = clock;
        this.hours = new ServiceDayCache<>(clock, this::query, ROWS, ServiceDayCache.positionCount(jdbc),
                summaries.store("stop-dwell", () -> LOGIC, Hour.class));
        summaries.onOutdated("stop-dwell", hours::forget);
        this.cache = new RecentCache<>(clock, CACHE_FOR, 16);
    }

    /** Works out the last week's days at startup, in the background, so Home doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("stop-dwell", StopDwellService.class,
                () -> top(new ReportWindow(now.minus(Duration.ofDays(7)), now), null, null));
    }

    /**
     * The {@link #TOP} stops with the longest mean dwell in a window, of at least {@link #MIN_VISITS} visits.
     *
     * @param route  one route's stops; null for every route's, a stop's visits on all its routes together
     * @param routes with no route given, only these routes' visits (an operator's); null for all
     */
    public List<PositionHistoryDto.StopDwell> top(ReportWindow window, String route, Collection<String> routes) {
        String key = window + "|" + route + "|" + (routes == null ? "*" : String.join(",", Set.copyOf(routes)));
        return cache.get(key, () -> rank(hours.get(window.from(), window.to()), route,
                routes == null ? null : Set.copyOf(routes)));
    }

    static List<PositionHistoryDto.StopDwell> rank(List<Hour> hours, String route, Set<String> routes) {
        Map<String, long[]> byStop = new LinkedHashMap<>();
        for (Hour h : hours) {
            if (route != null ? h.route().equals(route) : routes == null || routes.contains(h.route())) {
                long[] s = byStop.computeIfAbsent(h.stop(), k -> new long[2]);
                s[0] += h.visits();
                s[1] += h.seconds();
            }
        }
        return byStop.entrySet().stream().filter(e -> e.getValue()[0] >= MIN_VISITS)
                .map(e -> new PositionHistoryDto.StopDwell(e.getKey(),
                        round((double) e.getValue()[1] / e.getValue()[0], 1), e.getValue()[0]))
                .sorted(Comparator.comparingDouble(PositionHistoryDto.StopDwell::meanSeconds).reversed()
                        .thenComparing(PositionHistoryDto.StopDwell::stop))
                .limit(TOP).toList();
    }

    /** The visits in {@code [from, to)} (within one service day), summed per hour, route and stop. */
    private List<Hour> query(Instant from, Instant to) {
        StopDwells dwells = new StopDwells(routeStops());
        reports.pairs(from, to, dwells.taker());
        return dwells.hours();
    }

    /** Each route's stops along the way, read again when the timetable changes. */
    private Map<String, List<Timetables.Stop>> routeStops() {
        String version = shapes.busesVersion();
        RouteStops current = routeStops;
        if (current == null || !current.version().equals(version)) {
            current = new RouteStops(version, timetables.stopsAlongRoutes());
            routeStops = current;
        }
        return current.stops();
    }
}
