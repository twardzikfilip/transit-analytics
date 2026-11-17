package com.transitanalytics.service;

import com.transitanalytics.recording.DelayOutliers;
import com.transitanalytics.dto.VehicleStatsDto;
import com.transitanalytics.dto.VehicleStatsDto.Incident;
import com.transitanalytics.dto.VehicleStatsDto.LineDay;
import com.transitanalytics.dto.VehicleStatsDto.LineSpell;
import com.transitanalytics.dto.VehicleStatsDto.Usage;
import com.transitanalytics.dto.VehicleStatsDto.Vehicle;
import com.transitanalytics.service.VehicleIncidents.Run;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.transitanalytics.service.ServiceDay.DAY_START_HOUR;
import static com.transitanalytics.service.ServiceDay.ZONE;
import static com.transitanalytics.service.TimetableMath.LINE_ORDER;
import static com.transitanalytics.service.TimetableMath.round;

/** Vehicles in detail: how long each vehicle served, on which lines, and where vehicles left their brigade. */
@Service
public class VehicleStatsService {

    /** Time windows, ending now. */
    public enum Window {
        DAY(Duration.ofDays(1)), WEEK(Duration.ofDays(7)), MONTH(Duration.ofDays(30));

        final Duration length;

        Window(Duration length) {
            this.length = length;
        }

        public static Window of(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new IllegalArgumentException("window must be day, week or month");
            }
        }
    }

    static final int TOP = 10;
    /** A report is computed at most this often per window; the month's takes a few seconds. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** A service day's segments are kept once it ended this long ago (late reports have arrived by then). */
    static final Duration DAY_SETTLES = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<Window, VehicleStatsDto.Report> cache = new ConcurrentHashMap<>();
    /** Segments of service days that are over, by day. */
    private final Map<LocalDate, List<Segment>> daySegments = new ConcurrentHashMap<>();

    public VehicleStatsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Reads the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        WarmUp.inBackground("vehicle-stats", VehicleStatsService.class, () -> report(Window.MONTH));
    }

    /** The report for the window ending now. */
    public VehicleStatsDto.Report report(Window window) {
        Instant now = clock.instant();
        VehicleStatsDto.Report cached = cache.get(window);
        if (cached != null && cached.to().plus(CACHE_FOR).isAfter(now)) {
            return cached;
        }
        VehicleStatsDto.Report report = report(window, now.minus(window.length), now);
        cache.put(window, report);
        return report;
    }

    /**
     * Time on one line and brigade of one vehicle on one service day.
     *
     * @param seconds   time in service: each report counts until the vehicle's next one, up to a minute
     * @param lastDelay delay at the last report
     * @param lastStop  next stop at the last report
     */
    record Segment(Vehicle vehicle, LocalDate day, String brigade, String line, Instant first, Instant last,
                   double seconds, Integer lastDelay, String lastStop) {
    }

    VehicleStatsDto.Report report(Window window, Instant from, Instant to) {
        List<Segment> segments = segments(from, to);
        List<Usage> usage = usage(segments);
        List<Usage> byHours = usage.stream().sorted(Comparator.comparingDouble(Usage::hours).reversed()).toList();
        List<Usage> byFewestHours = usage.stream().sorted(Comparator.comparingDouble(Usage::hours)).toList();
        List<Usage> byLines = usage.stream().sorted(Comparator.comparingInt((Usage u) -> u.lines().size())
                .reversed().thenComparing(Comparator.comparingDouble(Usage::hours).reversed())).toList();
        List<Usage> byFewestLines = usage.stream().sorted(Comparator.comparingInt((Usage u) -> u.lines().size())
                .thenComparing(Comparator.comparingDouble(Usage::hours).reversed())).toList();
        return new VehicleStatsDto.Report(window.name().toLowerCase(Locale.ROOT), from, to, usage.size(),
                top(byHours), top(byFewestHours), top(byLines), top(byFewestLines), lineDays(segments),
                incidents(segments, schedules(segments)));
    }

    private static <T> List<T> top(List<T> list) {
        return List.copyOf(list.subList(0, Math.min(TOP, list.size())));
    }

    /**
     * Segments in {@code [from, to)}, one service day at a time. Days that are over don't change, so their segments
     * are kept; only the days at the window's edges (cut by it, or still running) are read again.
     */
    private List<Segment> segments(Instant from, Instant to) {
        Instant now = clock.instant();
        List<Segment> all = new ArrayList<>();
        for (LocalDate day = ServiceDay.serviceDay(from); !ServiceDay.start(day).isAfter(to.minusNanos(1)); day = day.plusDays(1)) {
            Instant start = ServiceDay.start(day);
            Instant end = ServiceDay.start(day.plusDays(1));
            boolean whole = !start.isBefore(from) && !end.isAfter(to) && end.plus(DAY_SETTLES).isBefore(now);
            if (whole) {
                all.addAll(daySegments.computeIfAbsent(day, d -> query(start, end)));
            } else {
                all.addAll(query(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end));
            }
        }
        // Days older than the longest window are not needed again.
        LocalDate oldest = ServiceDay.serviceDay(now.minus(Window.MONTH.length)).minusDays(1);
        daySegments.keySet().removeIf(d -> d.isBefore(oldest));
        all.sort(Comparator.comparing(Segment::first));
        return all;
    }

    private List<Segment> query(Instant from, Instant to) {
        // Reports on a line, per vehicle in time order. Filtering on observed_at alone (the hypertable's time column)
        // gives the planner good estimates; reports are fetched within seconds of being observed.
        return jdbc.query("""
                WITH r AS (
                    SELECT vehicle_id, coalesce(fetched_at, observed_at) AS poll, route_name, nullif(brigade, '') AS brigade,
                           delay_seconds, next_stop_name
                    FROM position_sample
                    WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''),
                spans AS (
                    SELECT *, date_part('epoch', least(lead(poll) OVER (PARTITION BY vehicle_id ORDER BY poll),
                                                      poll + interval '1 minute') - poll) AS seconds,
                           ((poll AT TIME ZONE '%1$s') - interval '%2$d hours')::date AS day,
                           -- Each query covers at most one service day, so this is the segment's last report.
                           row_number() OVER (PARTITION BY vehicle_id, brigade, route_name ORDER BY poll DESC) AS from_end
                    FROM r),
                seg AS (
                    SELECT vehicle_id, day, brigade, route_name, min(poll) AS first, max(poll) AS last,
                           sum(seconds) AS seconds,
                           max(delay_seconds) FILTER (WHERE from_end = 1) AS last_delay,
                           max(next_stop_name) FILTER (WHERE from_end = 1) AS last_stop
                    FROM spans
                    GROUP BY vehicle_id, day, brigade, route_name)
                SELECT s.*, v.vehicle_number, v.model, v.agency
                FROM seg s JOIN vehicle v ON v.id = s.vehicle_id""".formatted(ZONE.getId(), DAY_START_HOUR),
                (rs, i) -> new Segment(
                        new Vehicle(rs.getLong("vehicle_id"), rs.getString("vehicle_number"), rs.getString("model"),
                                rs.getString("agency")),
                        rs.getObject("day", LocalDate.class), rs.getString("brigade"), rs.getString("route_name"),
                        rs.getTimestamp("first").toInstant(), rs.getTimestamp("last").toInstant(),
                        rs.getDouble("seconds"), (Integer) rs.getObject("last_delay"), rs.getString("last_stop")),
                Timestamp.from(from), Timestamp.from(to));
    }

    static List<Usage> usage(List<Segment> segments) {
        Map<Long, List<Segment>> byVehicle = segments.stream()
                .collect(Collectors.groupingBy(s -> s.vehicle().id(), LinkedHashMap::new, Collectors.toList()));
        return byVehicle.values().stream().map(list -> new Usage(list.get(0).vehicle(),
                round(list.stream().mapToDouble(Segment::seconds).sum() / 3600, 1),
                (int) list.stream().map(Segment::day).distinct().count(),
                list.stream().map(Segment::line).collect(Collectors.toCollection(() -> new TreeSet<>(LINE_ORDER)))
                        .stream().toList(),
                list.stream().map(Segment::first).min(Comparator.naturalOrder()).orElseThrow(),
                list.stream().map(Segment::last).max(Comparator.naturalOrder()).orElseThrow())).toList();
    }

    /** Vehicle days with two or more lines, newest day first, then the most lines. */
    static List<LineDay> lineDays(List<Segment> segments) {
        Map<String, List<Segment>> byVehicleDay = segments.stream().collect(Collectors.groupingBy(
                s -> s.vehicle().id() + "|" + s.day(), LinkedHashMap::new, Collectors.toList()));
        List<LineDay> days = new ArrayList<>();
        for (List<Segment> list : byVehicleDay.values()) {
            Map<String, List<Segment>> byLine = list.stream()
                    .collect(Collectors.groupingBy(Segment::line, LinkedHashMap::new, Collectors.toList()));
            if (byLine.size() < 2) {
                continue;
            }
            List<LineSpell> spells = byLine.entrySet().stream().map(e -> new LineSpell(e.getKey(),
                            e.getValue().stream().map(Segment::brigade).filter(Objects::nonNull).distinct()
                                    .collect(Collectors.joining(", ")),
                            e.getValue().stream().map(Segment::first).min(Comparator.naturalOrder()).orElseThrow(),
                            e.getValue().stream().map(Segment::last).max(Comparator.naturalOrder()).orElseThrow(),
                            round(e.getValue().stream().mapToDouble(Segment::seconds).sum() / 3600, 1)))
                    .sorted(Comparator.comparing(LineSpell::from)).toList();
            days.add(new LineDay(list.get(0).day(), list.get(0).vehicle(), spells));
        }
        days.sort(Comparator.comparing(LineDay::day).reversed()
                .thenComparing(Comparator.comparingInt((LineDay d) -> d.lines().size()).reversed())
                .thenComparing(d -> d.vehicle().number()));
        return days;
    }

    /** Each day's scheduled runs per brigade, from the timetable services that ran that day. */
    private Map<LocalDate, Map<String, List<Run>>> schedules(List<Segment> segments) {
        TreeSet<LocalDate> days = segments.stream().map(Segment::day).collect(Collectors.toCollection(TreeSet::new));
        if (days.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, List<Run>>> byService = new HashMap<>();
        Map<LocalDate, Map<String, List<Run>>> byDay = new HashMap<>();
        jdbc.query("SELECT date, service_id FROM calendar_date WHERE exception_type = 1 AND date BETWEEN ? AND ?",
                rs -> {
                    LocalDate day = rs.getObject("date", LocalDate.class);
                    Map<String, List<Run>> runs = byService.computeIfAbsent(rs.getString("service_id"), this::runs);
                    runs.forEach((brigade, list) -> byDay.computeIfAbsent(day, d -> new HashMap<>())
                            .merge(brigade, list, (a, b) -> {
                                List<Run> all = new ArrayList<>(a);
                                all.addAll(b);
                                all.sort(Comparator.comparingInt(Run::start));
                                return all;
                            }));
                }, days.first(), days.last());
        return byDay;
    }

    /** Runs per brigade of one timetable service. */
    private Map<String, List<Run>> runs(String serviceId) {
        Map<String, List<Run>> runs = new HashMap<>();
        jdbc.query("""
                SELECT %s AS brigade,
                       extract(epoch FROM min(st.departure_time)::interval)::int AS start_s,
                       extract(epoch FROM max(st.arrival_time)::interval)::int AS end_s
                FROM trip t JOIN stop_time st ON st.trip_id = t.id
                WHERE t.service_id = ?
                GROUP BY t.id, brigade
                ORDER BY start_s""".formatted(ServiceDay.BRIGADE_SQL),
                rs -> {
                    runs.computeIfAbsent(rs.getString("brigade"), b -> new ArrayList<>())
                            .add(new Run(rs.getInt("start_s"), rs.getInt("end_s")));
                }, serviceId);
        return runs;
    }

    static List<Incident> incidents(List<Segment> segments, Map<LocalDate, Map<String, List<Run>>> schedules) {
        Map<LocalDate, Instant> lastPoll = new HashMap<>();
        for (Segment s : segments) {
            lastPoll.merge(s.day(), s.last(), (a, b) -> a.isAfter(b) ? a : b);
        }
        // The vehicle's last segment on each brigade it served that day.
        Map<String, Segment> lastOnBrigade = new LinkedHashMap<>();
        for (Segment s : segments) {
            if (s.brigade() != null) {
                lastOnBrigade.merge(s.vehicle().id() + "|" + s.day() + "|" + s.brigade(), s,
                        (a, b) -> a.last().isAfter(b.last()) ? a : b);
            }
        }
        // Lookups for where the vehicle went next and who took over its brigade.
        Map<String, List<Segment>> byVehicleDay = segments.stream()
                .collect(Collectors.groupingBy(s -> s.vehicle().id() + "|" + s.day()));
        Map<String, List<Segment>> byBrigadeDay = segments.stream().filter(s -> s.brigade() != null)
                .collect(Collectors.groupingBy(s -> s.brigade() + "|" + s.day()));
        List<Incident> incidents = new ArrayList<>();
        for (Segment s : lastOnBrigade.values()) {
            List<Run> runs = schedules.getOrDefault(s.day(), Map.of()).get(s.brigade());
            if (runs == null || !VehicleIncidents.recordedAfter(s.last(), lastPoll.get(s.day()))) {
                continue;
            }
            Instant dayStart = ServiceDay.gtfsTimeZero(s.day());
            // Compare with where the vehicle was in its schedule; delays over 30 minutes are taken as feed errors.
            Integer delay = s.lastDelay() == null || Math.abs(s.lastDelay()) > DelayOutliers.THRESHOLD_SECONDS
                    ? null : s.lastDelay();
            int scheduled = (int) (Duration.between(dayStart, s.last()).toSeconds() - (delay == null ? 0 : delay));
            VehicleIncidents.Missed missed = VehicleIncidents.missed(runs, scheduled);
            if (missed == null) {
                continue;
            }
            Segment next = byVehicleDay.get(s.vehicle().id() + "|" + s.day()).stream()
                    .filter(o -> o.first().isAfter(s.last()))
                    .min(Comparator.comparing(Segment::first)).orElse(null);
            if (next != null && next.line().equals(s.line())) {
                continue; // it went on serving the line, under another brigade
            }
            Segment replacement = byBrigadeDay.get(s.brigade() + "|" + s.day()).stream()
                    .filter(o -> o.vehicle().id() != s.vehicle().id() && o.last().isAfter(s.last()))
                    .min(Comparator.comparing(Segment::first)).orElse(null);
            incidents.add(new Incident(s.day(), s.vehicle(), next == null ? "disappeared" : "switched", s.line(),
                    s.brigade(), s.last(), s.lastStop(), s.lastDelay(), dayStart.plusSeconds(missed.until()),
                    dayStart.plusSeconds(missed.end()), missed.missedRuns(),
                    next == null ? null : next.line(), next == null ? null : next.brigade(),
                    next == null ? null : next.first(),
                    replacement == null ? null : replacement.vehicle(),
                    replacement == null ? null : laterOf(replacement.first(), s.last())));
        }
        incidents.sort(Comparator.comparing(Incident::lastSeen).reversed());
        return incidents;
    }

    private static Instant laterOf(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
