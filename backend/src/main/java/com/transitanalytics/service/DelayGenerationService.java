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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Delay generation: where on the network, and when, buses gain or recover delay between stops. */
@Service
public class DelayGenerationService {

    /** A report is computed at most this often per window (and line). */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Two passages further apart than this aren't consecutive (missing data). */
    static final Duration MAX_POLL_GAP = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ServiceDayCache<Run> runs;
    /** Each window's runs, read for the network report and reused for line reports while it is cached. */
    private final Map<TimeWindow, WindowRuns> windowRuns = new ConcurrentHashMap<>();
    private final Map<TimeWindow, DelayGenerationDto.Report> networks = new ConcurrentHashMap<>();
    private final Map<String, DelayGenerationDto.LineReport> lines = new ConcurrentHashMap<>();

    public DelayGenerationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.runs = new ServiceDayCache<>(clock, this::query);
    }

    /** Reads the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        WarmUp.inBackground("delay-generation", DelayGenerationService.class, () -> report(TimeWindow.MONTH));
    }

    /** The whole network, for the window ending now. */
    public DelayGenerationDto.Report report(TimeWindow window) {
        Instant now = clock.instant();
        DelayGenerationDto.Report cached = networks.get(window);
        if (cached != null && cached.to().plus(CACHE_FOR).isAfter(now)) {
            return cached;
        }
        WindowRuns current = runs(window, now);
        DelayGenerationDto.Report report = report(window, current.from(), current.to(), current.runs());
        networks.put(window, report);
        return report;
    }

    /** One line, for the window ending now. */
    public DelayGenerationDto.LineReport line(TimeWindow window, String line) {
        Instant now = clock.instant();
        String key = window + "|" + line;
        DelayGenerationDto.LineReport cached = lines.get(key);
        if (cached != null && cached.to().plus(CACHE_FOR).isAfter(now)) {
            return cached;
        }
        lines.values().removeIf(r -> !r.to().plus(CACHE_FOR).isAfter(now));
        WindowRuns current = runs(window, now);
        DelayGenerationDto.LineReport report = line(window, current.from(), current.to(), line, current.runs());
        lines.put(key, report);
        return report;
    }

    /** The runs of a window ending at most {@link #CACHE_FOR} ago. */
    private record WindowRuns(Instant from, Instant to, List<Run> runs) {
    }

    private WindowRuns runs(TimeWindow window, Instant now) {
        WindowRuns cached = windowRuns.get(window);
        if (cached != null && cached.to().plus(CACHE_FOR).isAfter(now)) {
            return cached;
        }
        Instant from = now.minus(window.length);
        WindowRuns fresh = new WindowRuns(from, now, runs.get(from, now));
        windowRuns.put(window, fresh);
        return fresh;
    }

    DelayGenerationDto.Report report(TimeWindow window, Instant from, Instant to) {
        return report(window, from, to, runs.get(from, to));
    }

    private DelayGenerationDto.Report report(TimeWindow window, Instant from, Instant to, List<Run> all) {
        int days = days(all);
        return new DelayGenerationDto.Report(window.key(), from, to, days, all.size(), DelayGeneration.BANDS,
                DelayGeneration.stretches(all), DelayGeneration.lines(all, days));
    }

    DelayGenerationDto.LineReport line(TimeWindow window, Instant from, Instant to, String line) {
        return line(window, from, to, line, runs.get(from, to));
    }

    private DelayGenerationDto.LineReport line(TimeWindow window, Instant from, Instant to, String line,
                                               List<Run> all) {
        List<Run> lineRuns = all.stream().filter(r -> r.line().equals(line)).toList();
        return DelayGeneration.lineReport(window.key(), from, to, line, lineRuns, days(lineRuns));
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
