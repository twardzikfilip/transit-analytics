package com.transitanalytics.delays;

import com.transitanalytics.data.Report;
import com.transitanalytics.data.Reports;
import com.transitanalytics.common.DaySummaries;
import com.transitanalytics.common.RecentCache;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.common.ServiceDayCache;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.delays.DelayGeneration.Passage;
import com.transitanalytics.delays.DelayGeneration.Run;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.recording.DelayOutliers;
import com.transitanalytics.timetable.StopPlaces;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Delay generation: where on the network, and when, buses gain or recover delay between stops. */
@Service
public class DelayGenerationService {

    /** A report is computed at most this often per window (and route). */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /**
     * A run starts at its first passage; passages at most {@link DelayGeneration#MAX_GAP_SECONDS} apart join it. A
     * passage needs the report before it (at most {@link Reports#PAIR_GAP} earlier), so a run's query starts that much
     * before the gap before it; and a run shows only from its {@link DelayGeneration#MIN_PASSAGES}th passage on.
     */
    static final ServiceDayCache.Rows<Run> ROWS = new ServiceDayCache.Rows<>(
            r -> Instant.ofEpochSecond(r.times()[0]), r -> Instant.ofEpochSecond(r.times()[r.size() - 1]),
            Duration.ofSeconds(DelayGeneration.MAX_GAP_SECONDS),
            Duration.ofSeconds(DelayGeneration.MAX_GAP_SECONDS).plus(Reports.PAIR_GAP).plusMinutes(1),
            Duration.ofSeconds((long) (DelayGeneration.MIN_PASSAGES - 1) * DelayGeneration.MAX_GAP_SECONDS));

    /** Raised whenever what is worked out for a day changes: stored days are out of date then. */
    private static final String LOGIC = "2";

    private final JdbcTemplate jdbc;
    private final Reports reports;
    private final Clock clock;
    private final ServiceDayCache<Run> runs;
    /** Each window's runs, read for the network report and reused for its route reports while kept. */
    private final RecentCache<ReportWindow, List<Run>> windowRuns;
    private final RecentCache<ReportWindow, DelayGenerationDto.Report> networks;
    private final RecentCache<String, DelayGenerationDto.RouteReport> routes;

    public DelayGenerationService(JdbcTemplate jdbc, Reports reports, Clock clock, DaySummaries summaries) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.clock = clock;
        this.runs = new ServiceDayCache<>(clock, this::query, ROWS, ServiceDayCache.positionCount(jdbc),
                summaries.store("delay-generation", () -> LOGIC, Run.class));
        summaries.onOutdated("delay-generation", runs::forget);
        this.windowRuns = new RecentCache<>(clock, CACHE_FOR, 8);
        this.networks = new RecentCache<>(clock, CACHE_FOR, 8);
        this.routes = new RecentCache<>(clock, CACHE_FOR, 16);
    }

    /** Reads the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("delay-generation", DelayGenerationService.class,
                () -> report(new ReportWindow(now.minus(Duration.ofDays(30)), now)));
    }

    /** The whole network in a window. */
    public DelayGenerationDto.Report report(ReportWindow window) {
        return networks.get(window, () -> {
            List<Run> all = runs(window);
            int days = days(all);
            return new DelayGenerationDto.Report(window.from(), window.to(), days, all.size(),
                    DelayGeneration.BANDS, DelayGeneration.stretches(all), DelayGeneration.routes(all, days));
        });
    }

    /** One route in a window. */
    public DelayGenerationDto.RouteReport route(ReportWindow window, String route) {
        return routes.get(window + "|" + route, () -> {
            List<Run> routeRuns = runs(window).stream().filter(r -> r.route().equals(route)).toList();
            return DelayGeneration.routeReport(window.from(), window.to(), route, routeRuns, days(routeRuns));
        });
    }

    private List<Run> runs(ReportWindow window) {
        return windowRuns.get(window, () -> runs.get(window.from(), window.to()));
    }

    /** Service days with runs; per-day figures are over these, not the window (recording may have gaps). */
    private static int days(List<Run> runs) {
        return (int) Math.max(1, runs.stream().map(Run::day).distinct().count());
    }

    /**
     * Runs in {@code [from, to)}. A passage is the second report of a pair ({@link Reports#pairs}) whose next stop is
     * one further on than in the first, on the same brigade and headsign: the bus has just left the first report's
     * next stop, and its delay then is the delay on leaving it. Skipping a stop between two reports isn't a passage
     * (the delay belongs to a later stop), nor is a delay over 30 minutes (a feed error).
     */
    private List<Run> query(Instant from, Instant to) {
        List<Passage> passages = new ArrayList<>();
        reports.pairs(from, to, pair -> {
            Report a = pair.from();
            Report b = pair.to();
            Integer seq = a.nextSeq();
            if (seq != null && b.nextSeq() != null && b.nextSeq() == seq + 1 && pair.stop() != null
                    && Objects.equals(a.brigade(), b.brigade()) && Objects.equals(a.headsign(), b.headsign())
                    && b.delay() != null && Math.abs(b.delay()) <= DelayOutliers.THRESHOLD_SECONDS) {
                passages.add(new Passage(b.vehicleId(), b.vehicle(), b.route(), b.brigade(), b.headsign(), seq,
                        pair.stop(), b.time(), b.delay(), b.lat(), b.lon()));
            }
        });
        return DelayGeneration.runs(passages, new DelayGeneration.StopResolver(stops()));
    }

    private List<StopPlaces.StopRef> stops() {
        return jdbc.query("SELECT name, latitude, longitude FROM stop WHERE feed <> '" + TimetableFeed.RAIL + "'",
                (rs, i) -> new StopPlaces.StopRef(rs.getString("name"), (Double) rs.getObject("latitude"),
                        (Double) rs.getObject("longitude")));
    }
}
