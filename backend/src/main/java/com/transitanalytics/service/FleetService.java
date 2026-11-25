package com.transitanalytics.service;

import com.transitanalytics.domain.VehicleClass;
import com.transitanalytics.dto.FleetDto.Breakdown;
import com.transitanalytics.dto.FleetDto.Usage;
import com.transitanalytics.dto.FleetDto.Vehicle;
import com.transitanalytics.dto.FleetDto;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.transitanalytics.service.ServiceDay.DAY_START_HOUR;
import static com.transitanalytics.service.ServiceDay.ZONE;
import static com.transitanalytics.service.TimetableMath.LINE_ORDER;
import static com.transitanalytics.service.TimetableMath.round;

/**
 * The fleet: how long each bus served in a time window and on which lines, the fleet by model, the buses that broke
 * down (as found on Abnormal trips) and when they came back, and one bus's runs day by day.
 */
@Service
public class FleetService {

    static final int TOP = 10;
    /** A report is computed at most this often per window; a month's takes a few seconds. */
    static final Duration CACHE_FOR = Duration.ofMinutes(2);
    /** Reports further apart than this (or on another line, brigade or headsign) belong to different runs. */
    static final Duration RUN_GAP = Duration.ofMinutes(5);
    /** A gap between two runs this long or longer is a break, as on a brigade's timetable. */
    static final Duration BREAK = Duration.ofMinutes(30);
    /** Each report counts as in service until the bus's next one, up to this long (gaps in the feed don't count). */
    static final Duration REPORT_COUNTS_FOR = Duration.ofMinutes(1);
    /** A report is polled at most this long after it was made (seconds; on 23 Nov 2026 at most 4.6 minutes). */
    static final Duration POLLED_AFTER = Duration.ofMinutes(15);
    static final String UNKNOWN_MODEL = "unknown";

    /** Raised whenever what is worked out for a day changes: stored days are out of date then. */
    private static final String LOGIC = "1";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TripAnomalyService trips;
    private final ServiceDayCache<Segment> segments;
    private final RecentCache<ReportWindow, Figures> figures;

    public FleetService(JdbcTemplate jdbc, Clock clock, TripAnomalyService trips, DaySummaries summaries) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.trips = trips;
        this.segments = new ServiceDayCache<>(clock, this::query, ROWS, ServiceDayCache.positionCount(jdbc),
                summaries.store("fleet", () -> LOGIC, Segment.class));
        summaries.onOutdated("fleet", segments::forget);
        this.figures = new RecentCache<>(clock, CACHE_FOR, 8);
    }

    /** Reads the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("fleet", FleetService.class,
                () -> report(new ReportWindow(now.minus(Duration.ofDays(30)), now), Set.of()));
    }

    /**
     * The fleet in a window.
     *
     * @param agencies the operators whose buses to include; empty for all
     */
    public FleetDto.Report report(ReportWindow window, Set<String> agencies) {
        return figures.get(window, () -> figures(window)).report(agencies);
    }

    /**
     * Time on one line of one vehicle (its id: its number, model and class are looked up when the figures are made,
     * as they change) in one hour (from {@code hour}) of one service day.
     */
    record Segment(long vehicleId, LocalDate day, Instant hour, String line, double seconds) {
    }

    /**
     * A segment is an hour: it fills with the reports in it (each counting until the next, at most
     * {@link #REPORT_COUNTS_FOR}). A window takes the hours starting in it. Hours go by when reports were polled, the
     * query by when they were made: up to {@link #POLLED_AFTER} earlier.
     */
    static final ServiceDayCache.Rows<Segment> ROWS = new ServiceDayCache.Rows<>(Segment::hour,
            s -> s.hour().plus(Duration.ofHours(1)), REPORT_COUNTS_FOR, POLLED_AFTER, Duration.ofHours(1));

    /** A window's figures for all operators. */
    record Figures(Instant from, Instant to, List<Vehicle> fleet, List<Usage> usage, List<Breakdown> breakdowns) {

        FleetDto.Report report(Set<String> agencies) {
            Predicate<Vehicle> chosen = v -> agencies.isEmpty() || agencies.contains(v.agency());
            List<Vehicle> buses = fleet.stream().filter(chosen).toList();
            List<Usage> used = usage.stream().filter(u -> chosen.test(u.vehicle())).toList();
            List<Usage> all = withIdle(buses, used);
            List<Usage> leastFirst = used.stream()
                    .sorted(Comparator.comparingDouble(Usage::hours).thenComparing(u -> u.vehicle().number()))
                    .toList();
            return new FleetDto.Report(from, to, used.size(), buses.size(), agencies(fleet), models(buses, used),
                    classes(buses, used), makes(buses, used),
                    top(all.stream().filter(u -> u.hours() > 0).toList()), top(leastFirst), all,
                    breakdowns.stream().filter(b -> chosen.test(b.vehicle())).toList());
        }
    }

    /** The buses in use and those not (with 0 hours), the longest in service first. */
    static List<Usage> withIdle(List<Vehicle> buses, List<Usage> used) {
        Set<Long> seen = used.stream().map(u -> u.vehicle().id()).collect(Collectors.toSet());
        List<Usage> all = new ArrayList<>(used);
        buses.stream().filter(b -> !seen.contains(b.id())).forEach(b -> all.add(new Usage(b, 0, 0, List.of())));
        all.sort(Comparator.comparingDouble(Usage::hours).reversed().thenComparing(u -> u.vehicle().number(),
                LINE_ORDER));
        return all;
    }

    /** Operators of the fleet, the most buses first. */
    static List<FleetDto.Agency> agencies(List<Vehicle> fleet) {
        Map<String, Long> counts = fleet.stream().map(Vehicle::agency).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(a -> a, Collectors.counting()));
        return counts.entrySet().stream().map(e -> new FleetDto.Agency(e.getKey(), e.getValue().intValue()))
                .sorted(Comparator.comparingInt(FleetDto.Agency::vehicles).reversed()
                        .thenComparing(FleetDto.Agency::name))
                .toList();
    }

    /** Buses per model, in use and in the fleet: the most in use first. */
    static List<FleetDto.ModelCount> models(List<Vehicle> buses, List<Usage> used) {
        return counts(buses, used, FleetService::model, MOST_IN_USE);
    }

    /** Buses per size class, in use and in the fleet: smallest to largest, then those of unknown class. */
    static List<FleetDto.ModelCount> classes(List<Vehicle> buses, List<Usage> used) {
        return counts(buses, used, v -> v.vehicleClass() == null ? UNKNOWN_MODEL : v.vehicleClass().value(),
                Comparator.comparingInt(c -> c.model().equals(UNKNOWN_MODEL) ? Integer.MAX_VALUE
                        : VehicleClass.valueOf(c.model().toUpperCase(java.util.Locale.ROOT)).ordinal()));
    }

    /** Buses per make, the model name's first word ("Solaris"), in use and in the fleet: the most in use first. */
    static List<FleetDto.ModelCount> makes(List<Vehicle> buses, List<Usage> used) {
        return counts(buses, used, v -> make(v.model()), MOST_IN_USE);
    }

    /** A model's make: its first word; "unknown" without a model. */
    static String make(String model) {
        return model == null || model.isBlank() ? UNKNOWN_MODEL : model.strip().split("\\s+")[0];
    }

    private static final Comparator<FleetDto.ModelCount> MOST_IN_USE =
            Comparator.comparingInt(FleetDto.ModelCount::inUse).reversed()
                    .thenComparing(Comparator.comparingInt(FleetDto.ModelCount::fleet).reversed())
                    .thenComparing(FleetDto.ModelCount::model);

    /** Buses per key, in use and in the fleet (at least those in use), in the given order. */
    private static List<FleetDto.ModelCount> counts(List<Vehicle> buses, List<Usage> used,
                                                    java.util.function.Function<Vehicle, String> key,
                                                    Comparator<FleetDto.ModelCount> order) {
        Map<String, int[]> counts = new HashMap<>();
        buses.forEach(b -> counts.computeIfAbsent(key.apply(b), m -> new int[2])[1]++);
        used.forEach(u -> counts.computeIfAbsent(key.apply(u.vehicle()), m -> new int[2])[0]++);
        return counts.entrySet().stream()
                .map(e -> new FleetDto.ModelCount(e.getKey(), e.getValue()[0], Math.max(e.getValue()[0],
                        e.getValue()[1])))
                .sorted(order)
                .toList();
    }

    private static VehicleClass vehicleClass(String value) {
        return value == null ? null : VehicleClass.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static String model(Vehicle v) {
        return v.model() == null || v.model().isBlank() ? UNKNOWN_MODEL : v.model();
    }

    private static <T> List<T> top(List<T> list) {
        return List.copyOf(list.subList(0, Math.min(TOP, list.size())));
    }

    private Figures figures(ReportWindow window) {
        List<Vehicle> vehicles = vehicles();
        Map<Long, Vehicle> byId = vehicles.stream().collect(Collectors.toMap(Vehicle::id, v -> v));
        List<Vehicle> fleet = jdbc.queryForList("SELECT id FROM vehicle WHERE type = 'BUS'", Long.class).stream()
                .map(byId::get).filter(Objects::nonNull).toList();
        return new Figures(window.from(), window.to(), fleet, usage(segments.get(window.from(), window.to()), byId),
                breakdowns(trips.outcomes(window.from(), window.to()), byId));
    }

    private List<Vehicle> vehicles() {
        return jdbc.query("SELECT id, vehicle_number, model, agency, vehicle_class, operating_agency FROM vehicle",
                (rs, i) -> new Vehicle(rs.getLong("id"), rs.getString("vehicle_number"), rs.getString("model"),
                        rs.getString("agency"), vehicleClass(rs.getString("vehicle_class")),
                        rs.getString("operating_agency")));
    }

    static List<Usage> usage(List<Segment> segments, Map<Long, Vehicle> vehicles) {
        Map<Long, List<Segment>> byVehicle = segments.stream().filter(s -> vehicles.containsKey(s.vehicleId()))
                .collect(Collectors.groupingBy(Segment::vehicleId, LinkedHashMap::new, Collectors.toList()));
        return byVehicle.values().stream().map(list -> new Usage(vehicles.get(list.get(0).vehicleId()),
                        round(list.stream().mapToDouble(Segment::seconds).sum() / 3600, 1),
                        (int) list.stream().map(Segment::day).distinct().count(),
                        list.stream().map(Segment::line).collect(Collectors.toCollection(() -> new TreeSet<>(LINE_ORDER)))
                                .stream().toList()))
                .sorted(Comparator.comparingDouble(Usage::hours).reversed().thenComparing(u -> u.vehicle().number()))
                .toList();
    }

    /** Segments in {@code [from, to)}: per vehicle, service day and line, the time in service. */
    private List<Segment> query(Instant from, Instant to) {
        // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
        return jdbc.query("""
                WITH r AS (
                    SELECT vehicle_id, coalesce(fetched_at, observed_at) AS poll, route_name
                    FROM position_sample
                    WHERE observed_at >= ? AND observed_at < ? AND route_name <> ''),
                spans AS (
                    SELECT *, date_part('epoch', least(lead(poll) OVER (PARTITION BY vehicle_id ORDER BY poll),
                                                      poll + interval '%3$d seconds') - poll) AS seconds,
                           ((poll AT TIME ZONE '%1$s') - interval '%2$d hours')::date AS day,
                           date_trunc('hour', poll) AS hour
                    FROM r)
                SELECT vehicle_id, day, hour, route_name, sum(seconds) AS seconds
                FROM spans
                GROUP BY vehicle_id, day, hour, route_name"""
                        .formatted(ZONE.getId(), DAY_START_HOUR, REPORT_COUNTS_FOR.toSeconds()),
                (rs, i) -> new Segment(rs.getLong("vehicle_id"), rs.getObject("day", LocalDate.class),
                        rs.getTimestamp("hour").toInstant(), rs.getString("route_name"), rs.getDouble("seconds")),
                Timestamp.from(from), Timestamp.from(to));
    }

    // --- breakdowns ----------------------------------------------------------------------------------------------

    /** Whether an outcome is a breakdown: a change of bus whose replaced bus wasn't used again that day. */
    static boolean brokeDown(Outcome o) {
        return o.kind() == Kind.VEHICLE_CHANGED && o.trip() != null
                && TripHandovers.Fate.BROKEN_DOWN.key.equals(o.trip().changeFate()) && !o.vehicleIds().isEmpty();
    }

    /** The buses that broke down, the latest first, with when they served a line again. */
    private List<Breakdown> breakdowns(List<Outcome> outcomes, Map<Long, Vehicle> vehicles) {
        Instant now = clock.instant();
        List<Breakdown> list = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (!brokeDown(o)) {
                continue;
            }
            Vehicle bus = vehicles.get(o.vehicleIds().get(0));
            if (bus == null) {
                continue;
            }
            Instant at = o.trip().changeAt() != null ? o.trip().changeAt() : o.departure();
            Instant returned = returned(bus.id(), ServiceDay.start(ServiceDay.serviceDay(at).plusDays(1)));
            list.add(new Breakdown(bus, at, o.line(), o.trip().brigade(), o.trip().headsign(),
                    o.trip().changeStop(), returned, daysBetween(at, returned != null ? returned : now)));
        }
        list.sort(Comparator.comparing(Breakdown::at).reversed());
        return list;
    }

    static double daysBetween(Instant from, Instant to) {
        return round(Duration.between(from, to).toMinutes() / 1440.0, 1);
    }

    /** The bus's first report on a line from {@code after} on; null if none. */
    private Instant returned(long vehicleId, Instant after) {
        Timestamp first = jdbc.queryForObject("""
                SELECT min(observed_at) FROM position_sample
                WHERE vehicle_id = ? AND observed_at >= ? AND route_name <> ''""",
                Timestamp.class, vehicleId, Timestamp.from(after));
        return first == null ? null : first.toInstant();
    }

    // --- one bus -------------------------------------------------------------------------------------------------

    /** A report of one bus: when it was polled, and on which line (blank when not in service), brigade and headsign. */
    record Report(Instant time, String line, String brigade, String headsign) {

        boolean inService() {
            return line != null && !line.isBlank();
        }
    }

    /** One bus in a window: its runs and breaks per service day, and the abnormal trips it was involved in. */
    public FleetDto.VehicleDetail vehicle(long id, ReportWindow window) {
        Vehicle vehicle = jdbc.query("""
                SELECT id, vehicle_number, model, agency, vehicle_class, operating_agency FROM vehicle WHERE id = ?""",
                (rs, i) -> new Vehicle(rs.getLong("id"), rs.getString("vehicle_number"), rs.getString("model"),
                        rs.getString("agency"), vehicleClass(rs.getString("vehicle_class")),
                        rs.getString("operating_agency")), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Vehicle", id));
        List<Report> reports = jdbc.query("""
                SELECT coalesce(fetched_at, observed_at) AS poll, route_name, nullif(brigade, '') AS brigade,
                       nullif(headsign, '') AS headsign
                FROM position_sample
                WHERE vehicle_id = ? AND observed_at >= ? AND observed_at < ?
                ORDER BY observed_at""",
                (rs, i) -> new Report(rs.getTimestamp("poll").toInstant(), rs.getString("route_name"),
                        rs.getString("brigade"), rs.getString("headsign")),
                id, Timestamp.from(window.from()), Timestamp.from(window.to()));
        Timestamp last = jdbc.queryForObject("""
                SELECT max(observed_at) FROM position_sample
                WHERE vehicle_id = ? AND observed_at < ? AND route_name <> ''""",
                Timestamp.class, id, Timestamp.from(window.to()));
        List<FleetDto.Day> days = days(reports);
        double hours = round(days.stream().mapToDouble(FleetDto.Day::hours).sum(), 1);
        List<String> lines = reports.stream().filter(Report::inService).map(Report::line)
                .collect(Collectors.toCollection(() -> new TreeSet<>(LINE_ORDER))).stream().toList();
        return new FleetDto.VehicleDetail(vehicle, window.from(), window.to(), hours, lines,
                last == null ? null : last.toInstant(), days,
                incidents(id, trips.outcomes(window.from(), window.to())).stream()
                        .map(i -> i.withPaths(paths(i.timeline()))).toList());
    }

    /** Room around a breakdown's time line on its map. */
    static final Duration PATH_MARGIN = Duration.ofMinutes(2);

    /**
     * Where the buses of a breakdown's time line went over it (and {@link #PATH_MARGIN} either side), from their
     * recorded positions; in the time line's order of buses.
     */
    List<FleetDto.Path> paths(List<TripAnomalyDto.Span> timeline) {
        if (timeline.isEmpty()) {
            return List.of();
        }
        List<String> buses = timeline.stream().map(TripAnomalyDto.Span::vehicle).distinct().toList();
        Instant from = timeline.stream().map(TripAnomalyDto.Span::from).min(Comparator.naturalOrder()).orElseThrow()
                .minus(PATH_MARGIN);
        Instant to = timeline.stream().map(TripAnomalyDto.Span::to).max(Comparator.naturalOrder()).orElseThrow()
                .plus(PATH_MARGIN);
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        buses.forEach(b -> points.put(b, new ArrayList<>()));
        List<Object> args = new ArrayList<>(buses);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        jdbc.query("""
                SELECT v.vehicle_number, p.latitude, p.longitude
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE v.vehicle_number IN (%s) AND p.observed_at BETWEEN ? AND ?
                ORDER BY v.vehicle_number, p.observed_at""".formatted(
                        String.join(", ", Collections.nCopies(buses.size(), "?"))),
                rs -> {
                    points.get(rs.getString("vehicle_number")).add(new double[] {rs.getDouble("latitude"),
                            rs.getDouble("longitude")});
                },
                args.toArray());
        return points.entrySet().stream().map(e -> new FleetDto.Path(e.getKey(), e.getValue())).toList();
    }

    /**
     * A bus's service days from its reports (in time order): runs of consecutive reports on one line, brigade and
     * headsign at most {@link #RUN_GAP} apart, breaks of {@link #BREAK} or more between runs, and hours in service.
     */
    static List<FleetDto.Day> days(List<Report> reports) {
        Map<LocalDate, List<Report>> byDay = new LinkedHashMap<>();
        for (Report r : reports) {
            byDay.computeIfAbsent(ServiceDay.serviceDay(r.time()), d -> new ArrayList<>()).add(r);
        }
        List<FleetDto.Day> days = new ArrayList<>();
        byDay.forEach((day, list) -> {
            Instant start = ServiceDay.gtfsTimeZero(day);
            List<FleetDto.Run> runs = new ArrayList<>();
            double seconds = 0;
            Report first = null;
            Report previous = null;
            for (int i = 0; i < list.size(); i++) {
                Report r = list.get(i);
                Report next = i + 1 < list.size() ? list.get(i + 1) : null;
                if (r.inService()) {
                    Duration until = next == null ? REPORT_COUNTS_FOR : Duration.between(r.time(), next.time());
                    seconds += Math.min(until.toSeconds(), REPORT_COUNTS_FOR.toSeconds());
                }
                boolean continues = first != null && r.inService() && sameRun(previous, r);
                if (!continues) {
                    if (first != null) {
                        runs.add(run(start, first, previous));
                    }
                    first = r.inService() ? r : null;
                }
                previous = r;
            }
            if (first != null) {
                runs.add(run(start, first, previous));
            }
            if (runs.isEmpty()) {
                return;
            }
            List<FleetDto.Break> breaks = new ArrayList<>();
            for (int i = 1; i < runs.size(); i++) {
                int gapFrom = runs.get(i - 1).to();
                int gapTo = runs.get(i).from();
                if (gapTo - gapFrom >= BREAK.toSeconds()) {
                    breaks.add(new FleetDto.Break(gapFrom, gapTo));
                }
            }
            days.add(new FleetDto.Day(day, start, round(seconds / 3600, 1), runs, breaks));
        });
        return days;
    }

    private static boolean sameRun(Report a, Report b) {
        return a != null && a.inService() && Objects.equals(a.line(), b.line())
                && Objects.equals(a.brigade(), b.brigade()) && Objects.equals(a.headsign(), b.headsign())
                && Duration.between(a.time(), b.time()).compareTo(RUN_GAP) <= 0;
    }

    private static FleetDto.Run run(Instant dayStart, Report first, Report last) {
        return new FleetDto.Run((int) Duration.between(dayStart, first.time()).toSeconds(),
                (int) Duration.between(dayStart, last.time()).toSeconds(), first.line(), first.brigade(),
                first.headsign());
    }

    /** The trips a bus broke down on, and its brigade's trips it missed: the latest first. */
    static List<FleetDto.Incident> incidents(long vehicleId, List<Outcome> outcomes) {
        return outcomes.stream()
                .filter(o -> (brokeDown(o) && o.vehicleIds().get(0) == vehicleId)
                        || (o.kind() == Kind.MISSED && o.vehicleIds().contains(vehicleId)))
                .map(o -> new FleetDto.Incident(o.kind().key, ServiceDay.serviceDay(o.departure()), o.line(),
                        o.trip().brigade(), o.trip().headsign(), o.departure(), o.trip().note(), o.trip().story(),
                        o.trip().timeline(), List.of()))
                .sorted(Comparator.comparing(FleetDto.Incident::scheduledDeparture).reversed())
                .toList();
    }
}
