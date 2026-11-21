package com.transitanalytics.service;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.service.TripAnomalies.Outcome;
import com.transitanalytics.service.TripAnomalies.Report;
import com.transitanalytics.service.TripAnomalies.ScheduledTrip;
import com.transitanalytics.service.TripShapes.Shape;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Abnormal trips: scheduled trips not run, run only partly, lost, or off their route. */
@Service
public class TripAnomalyService {

    /** A window's report, and the running service day, are recomputed at most this often. */
    static final Duration CACHE_FOR = Duration.ofMinutes(1);
    /** The report lists at most this many abnormal trips (the latest); the counts cover them all. */
    static final int MAX_TRIPS = 2000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Each service day's outcomes, and when they were computed; a day long over is kept as it is. */
    private final Map<LocalDate, DayOutcomes> days = new ConcurrentHashMap<>();
    private final RecentCache<ReportWindow, TripAnomalyDto.Report> reports;
    private volatile Shapes shapes;

    public TripAnomalyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.reports = new RecentCache<>(clock, CACHE_FOR, 16);
    }

    private record DayOutcomes(Instant computed, List<Outcome> outcomes) {
    }

    /** Route shapes, for the timetable they were read from. */
    private record Shapes(String version, Map<String, Shape> byId) {
    }

    /** Judges the last month's days at startup, in the background, so the first visit doesn't wait for them. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        Instant now = clock.instant();
        WarmUp.inBackground("trip-anomalies", TripAnomalyService.class,
                () -> report(new ReportWindow(now.minus(Duration.ofDays(30)), now)));
    }

    /** Trips scheduled to run (for any part) in the window. */
    public TripAnomalyDto.Report report(ReportWindow window) {
        return reports.get(window, () -> report(window.from(), window.to(), clock.instant()));
    }

    /**
     * The outcomes of the trips scheduled to run (for any part) in {@code [from, to)}, judged as of now (a window
     * reaching into the future leaves its trips pending).
     */
    List<Outcome> outcomes(Instant from, Instant to) {
        return outcomes(from, to, clock.instant());
    }

    /** As {@link #outcomes(Instant, Instant)}, judged as of {@code now}. */
    List<Outcome> outcomes(Instant from, Instant to, Instant now) {
        Instant until = to.isAfter(now) ? now : to;
        List<Outcome> outcomes = new ArrayList<>();
        for (LocalDate day = ServiceDay.serviceDay(from).minusDays(1); !day.isAfter(ServiceDay.serviceDay(to));
             day = day.plusDays(1)) {
            for (Outcome o : outcomes(day, until)) {
                if (o.departure().isBefore(to) && !o.arrival().isBefore(from)) {
                    outcomes.add(o);
                }
            }
        }
        return outcomes;
    }

    TripAnomalyDto.Report report(Instant from, Instant to, Instant now) {
        List<Outcome> outcomes = outcomes(from, to, now);
        List<TripAnomalyDto.Trip> abnormal = outcomes.stream().filter(o -> o.kind().abnormal())
                .map(Outcome::trip)
                .sorted(Comparator.comparing(TripAnomalyDto.Trip::scheduledDeparture).reversed()
                        .thenComparing(TripAnomalyDto.Trip::line, TimetableMath.LINE_ORDER))
                .toList();
        return new TripAnomalyDto.Report(from, to, TripSummaries.summary(outcomes),
                TripSummaries.lines(outcomes), abnormal.size(),
                abnormal.subList(0, Math.min(MAX_TRIPS, abnormal.size())));
    }

    /**
     * A service day's outcomes as of {@code now}. A day whose trips were all settled when it was computed is kept;
     * the running day is computed again after {@link #CACHE_FOR}. Days older than {@link ServiceDayCache#KEEP_DAYS} are dropped.
     */
    private List<Outcome> outcomes(LocalDate day, Instant now) {
        DayOutcomes cached = days.get(day);
        if (cached != null && fresh(cached, day, now)) {
            return cached.outcomes();
        }
        List<Outcome> outcomes = judge(day, now);
        days.put(day, new DayOutcomes(now, outcomes));
        LocalDate oldest = ServiceDay.serviceDay(now).minusDays(ServiceDayCache.KEEP_DAYS);
        days.keySet().removeIf(d -> d.isBefore(oldest));
        return outcomes;
    }

    private List<Outcome> judge(LocalDate day, Instant now) {
        List<ScheduledTrip> trips = trips(day);
        if (trips.isEmpty()) {
            return List.of();
        }
        Instant from = ServiceDay.start(day);
        Instant to = reportsEnd(day, trips);
        List<Report> reports = reports(from, to.isAfter(now) ? now : to);
        return TripAnomalies.judge(trips, reports, TripAnomalies.Recording.of(reports), shapes().byId(), now);
    }

    /**
     * Until when reports count for a service day's trips: the next day's start, or later when trips run past it (the
     * night lines run until about 04:00), with time for late buses to finish.
     */
    static Instant reportsEnd(LocalDate day, List<ScheduledTrip> trips) {
        Instant end = ServiceDay.start(day.plusDays(1));
        for (ScheduledTrip trip : trips) {
            Instant finished = trip.arrival().plus(TripAnomalies.MATCH_TOLERANCE);
            if (finished.isAfter(end)) {
                end = finished;
            }
        }
        return end;
    }

    /** A service day's outcomes as worked out for the report, when they are kept and still current; else empty. */
    Optional<List<Outcome>> currentOutcomes(LocalDate day) {
        Instant now = clock.instant();
        DayOutcomes cached = days.get(day);
        return cached != null && fresh(cached, day, now) ? Optional.of(cached.outcomes())
                : Optional.empty();
    }

    /**
     * Some trips of a day (e.g. a brigade's) judged against the given reports (e.g. of the buses that ran them), with
     * recording gaps from every bus's reports that day, as of now: what the day's report would say, without judging
     * the whole day.
     */
    List<Outcome> judge(List<ScheduledTrip> trips, List<Report> reports, LocalDate day, Instant now) {
        Instant end = reportsEnd(day, trips);
        return TripAnomalies.judge(trips, reports, recording(ServiceDay.start(day), end.isAfter(now) ? now : end),
                shapes().byId(), now);
    }

    /** The minutes with any report in {@code [from, to)}: when recording went on. */
    private TripAnomalies.Recording recording(Instant from, Instant to) {
        return new TripAnomalies.Recording(new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT (extract(epoch FROM observed_at) / 60)::bigint FROM position_sample
                WHERE observed_at >= ? AND observed_at < ?""", Long.class, Timestamp.from(from), Timestamp.from(to))));
    }

    private boolean fresh(DayOutcomes cached, LocalDate day, Instant now) {
        // Night lines run past the next day's start: the day is settled once its last trip is.
        Instant lastArrival = cached.outcomes().stream().map(Outcome::arrival).max(Comparator.naturalOrder())
                .filter(a -> a.isAfter(ServiceDay.start(day.plusDays(1))))
                .orElse(ServiceDay.start(day.plusDays(1)));
        Instant settled = lastArrival.plus(TripAnomalies.SETTLE).plus(TripAnomalies.LOST_AFTER);
        return cached.computed().isAfter(settled) || cached.computed().plus(CACHE_FOR).isAfter(now);
    }

    /** The trips of the timetable services running that day, with their stops. */
    private List<ScheduledTrip> trips(LocalDate day) {
        return trips(day, null);
    }

    /** As {@link #trips(LocalDate)}, of one brigade only (null: all). */
    List<ScheduledTrip> trips(LocalDate day, String brigade) {
        record Row(long trip, String tripId, String line, String brigade, String headsign, String shapeId, int seq,
                   String stop, int departure, int arrival, double lat, double lon) {
        }
        List<Row> rows = jdbc.query("""
                SELECT t.id, t.gtfs_id, r.gtfs_id AS line, %s AS brigade, t.headsign, t.shape_id, st.stop_sequence,
                       s.name, s.latitude, s.longitude, extract(epoch FROM st.departure_time::interval)::int AS departure,
                       extract(epoch FROM st.arrival_time::interval)::int AS arrival
                FROM trip t JOIN route r ON r.id = t.route_id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE %s%s
                ORDER BY t.id, st.stop_sequence""".formatted(ServiceDay.BRIGADE_SQL, ServiceDay.TRIPS_OF_DAY_SQL,
                        brigade == null ? "" : " AND " + ServiceDay.BRIGADE_SQL + " = ?"),
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("gtfs_id"), rs.getString("line"),
                        rs.getString("brigade"), rs.getString("headsign"), rs.getString("shape_id"),
                        rs.getInt("stop_sequence"), StopPlaces.stopName(rs.getString("name")),
                        rs.getInt("departure"), rs.getInt("arrival"), rs.getDouble("latitude"),
                        rs.getDouble("longitude")),
                brigade == null ? ServiceDay.tripsOfDayArgs(day)
                        : new Object[] {day, day.plusDays(1), brigade});
        Map<Long, List<Row>> byTrip = new LinkedHashMap<>();
        rows.forEach(r -> byTrip.computeIfAbsent(r.trip(), k -> new ArrayList<>()).add(r));
        List<ScheduledTrip> trips = new ArrayList<>();
        for (List<Row> stops : byTrip.values()) {
            Row first = stops.get(0);
            Row last = stops.get(stops.size() - 1);
            Instant zero = ServiceDay.tripTimeZero(day, first.departure());
            trips.add(new ScheduledTrip(first.tripId(), first.line(), first.brigade(), first.headsign(),
                    zero.plusSeconds(first.departure()), zero.plusSeconds(last.arrival()),
                    stops.stream().mapToInt(Row::seq).toArray(), stops.stream().map(Row::stop).toArray(String[]::new),
                    stops.stream().mapToInt(row -> row.departure() - first.departure()).toArray(), first.shapeId(),
                    new double[] {last.lat(), last.lon()}));
        }
        return trips;
    }

    /** Reports on the timetable's lines in {@code [from, to)}, by vehicle and time. */
    private List<Report> reports(Instant from, Instant to) {
        return reports(from, to, null);
    }

    /**
     * As {@link #reports(Instant, Instant)}, of the buses that ran a brigade then (any of their reports, also on
     * other brigades), or of all buses (null).
     */
    List<Report> reports(Instant from, Instant to, String brigade) {
        // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
        return jdbc.query("""
                SELECT p.vehicle_id, v.vehicle_number, p.observed_at, p.route_name, p.brigade, p.headsign,
                       p.delay_seconds, p.next_stop_sequence, p.latitude, p.longitude
                FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
                WHERE p.observed_at >= ? AND p.observed_at < ? AND p.route_name IN (SELECT gtfs_id FROM route)%s
                ORDER BY p.vehicle_id, p.observed_at""".formatted(brigade == null ? "" : """
                 AND p.vehicle_id IN (SELECT vehicle_id FROM position_sample
                                      WHERE observed_at >= ? AND observed_at < ? AND brigade = ?)"""),
                (rs, i) -> new Report(rs.getLong("vehicle_id"), rs.getString("vehicle_number"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getString("route_name"),
                        rs.getString("brigade"), rs.getString("headsign"),
                        (Integer) rs.getObject("delay_seconds"), rs.getObject("next_stop_sequence", Integer.class),
                        rs.getDouble("latitude"), rs.getDouble("longitude")),
                brigade == null ? new Object[] {Timestamp.from(from), Timestamp.from(to)}
                        : new Object[] {Timestamp.from(from), Timestamp.from(to), Timestamp.from(from),
                        Timestamp.from(to), brigade});
    }

    /** All route shapes, read again when the timetable changes (another import). */
    private Shapes shapes() {
        Map<String, Object> v = jdbc.queryForMap("SELECT count(*) AS trips, max(id) AS last FROM trip");
        String version = v.get("trips") + ":" + v.get("last");
        Shapes current = shapes;
        if (current != null && current.version().equals(version)) {
            return current;
        }
        Map<String, List<double[]>> points = new HashMap<>();
        jdbc.query("SELECT shape_id, latitude, longitude FROM shape_point ORDER BY shape_id, sequence",
                rs -> {
                    points.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")});
                });
        Map<String, Shape> byId = new HashMap<>();
        points.forEach((id, list) -> byId.put(id, TripShapes.shape(list)));
        Shapes fresh = new Shapes(version, byId);
        shapes = fresh;
        return fresh;
    }
}
