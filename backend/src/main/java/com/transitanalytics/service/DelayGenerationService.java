package com.transitanalytics.service;

import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.recording.DelayOutliers;
import com.transitanalytics.service.DelayGeneration.Passage;
import com.transitanalytics.service.DelayGeneration.Run;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Delay generation: where on the network, and when, buses gain or recover delay between stops. */
@Service
public class DelayGenerationService {

    /** A report is computed at most this often per window (and line). */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Two passages further apart than this aren't consecutive (missing data). */
    static final Duration MAX_POLL_GAP = Duration.ofMinutes(3);

    /**
     * A run starts at its first passage; passages at most {@link DelayGeneration#MAX_GAP_SECONDS} apart join it. A
     * passage needs the report before it (at most {@link #MAX_POLL_GAP} earlier), so a run's query starts that much
     * before the gap before it; and a run shows only from its {@link DelayGeneration#MIN_PASSAGES}th passage on.
     */
    static final ServiceDayCache.Rows<Run> ROWS = new ServiceDayCache.Rows<>(
            r -> Instant.ofEpochSecond(r.times()[0]), r -> Instant.ofEpochSecond(r.times()[r.size() - 1]),
            Duration.ofSeconds(DelayGeneration.MAX_GAP_SECONDS),
            Duration.ofSeconds(DelayGeneration.MAX_GAP_SECONDS).plus(MAX_POLL_GAP).plusMinutes(1),
            Duration.ofSeconds((long) (DelayGeneration.MIN_PASSAGES - 1) * DelayGeneration.MAX_GAP_SECONDS));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ServiceDayCache<Run> runs;
    /** Each window's runs, read for the network report and reused for its line reports while kept. */
    private final RecentCache<ReportWindow, List<Run>> windowRuns;
    private final RecentCache<ReportWindow, DelayGenerationDto.Report> networks;
    private final RecentCache<String, DelayGenerationDto.LineReport> lines;

    public DelayGenerationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.runs = new ServiceDayCache<>(clock, this::query, ROWS, ServiceDayCache.positionCount(jdbc));
        this.windowRuns = new RecentCache<>(clock, CACHE_FOR, 8);
        this.networks = new RecentCache<>(clock, CACHE_FOR, 8);
        this.lines = new RecentCache<>(clock, CACHE_FOR, 16);
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
                    DelayGeneration.BANDS, DelayGeneration.stretches(all), DelayGeneration.lines(all, days));
        });
    }

    /** One line in a window. */
    public DelayGenerationDto.LineReport line(ReportWindow window, String line) {
        return lines.get(window + "|" + line, () -> {
            List<Run> lineRuns = runs(window).stream().filter(r -> r.line().equals(line)).toList();
            return DelayGeneration.lineReport(window.from(), window.to(), line, lineRuns, days(lineRuns));
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
     * Runs in {@code [from, to)}. A passage is a report whose next stop is one further on than in the bus's previous
     * report (on the same line, brigade and headsign, at most {@link #MAX_POLL_GAP} earlier): the bus has just left
     * the previous next stop, and its delay then is the delay on leaving it. Skipping a stop between two reports
     * isn't a passage (the delay belongs to a later stop), nor is a delay over 30 minutes (a feed error). Only the
     * timetable's lines: other operators have no schedule to be late against.
     */
    private List<Run> query(Instant from, Instant to) {
        List<Passage> passages = jdbc.query("""
                WITH r AS (
                    SELECT vehicle_id, observed_at AS t, route_name, brigade, headsign, delay_seconds,
                           next_stop_sequence AS seq, latitude, longitude,
                           lag(next_stop_sequence) OVER w AS pseq, lag(next_stop_name) OVER w AS pname,
                           lag(observed_at) OVER w AS pt, lag(route_name) OVER w AS pline,
                           lag(brigade) OVER w AS pbrigade, lag(headsign) OVER w AS pheadsign
                    FROM position_sample
                    WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''
                    WINDOW w AS (PARTITION BY vehicle_id ORDER BY observed_at))
                SELECT r.vehicle_id, v.vehicle_number, r.t, r.route_name, r.brigade, r.headsign, r.delay_seconds,
                       r.pseq, r.pname, r.latitude, r.longitude
                FROM r JOIN vehicle v ON v.id = r.vehicle_id
                WHERE r.seq = r.pseq + 1 AND r.t - r.pt <= interval '%d seconds' AND r.route_name = r.pline
                  AND r.brigade IS NOT DISTINCT FROM r.pbrigade AND r.headsign IS NOT DISTINCT FROM r.pheadsign
                  AND r.pname IS NOT NULL AND r.delay_seconds IS NOT NULL AND abs(r.delay_seconds) <= %d
                  AND r.route_name IN (SELECT gtfs_id FROM route)
                ORDER BY r.vehicle_id, r.t""".formatted(MAX_POLL_GAP.toSeconds(), DelayOutliers.THRESHOLD_SECONDS),
                (rs, i) -> new Passage(rs.getLong("vehicle_id"), rs.getString("vehicle_number"),
                        rs.getString("route_name"), rs.getString("brigade"), rs.getString("headsign"),
                        rs.getInt("pseq"), rs.getString("pname"), rs.getTimestamp("t").toInstant(),
                        rs.getInt("delay_seconds"), rs.getDouble("latitude"), rs.getDouble("longitude")),
                Timestamp.from(from), Timestamp.from(to));
        return DelayGeneration.runs(passages, new DelayGeneration.StopResolver(stops()));
    }

    private List<StopPlaces.StopRef> stops() {
        return jdbc.query("SELECT name, latitude, longitude FROM stop",
                (rs, i) -> new StopPlaces.StopRef(rs.getString("name"), (Double) rs.getObject("latitude"),
                        (Double) rs.getObject("longitude")));
    }
}
