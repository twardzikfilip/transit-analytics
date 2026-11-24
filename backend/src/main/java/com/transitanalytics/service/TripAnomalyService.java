package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.gtfs.GtfsProperties;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Abnormal trips: scheduled trips not run, run only partly, lost, or off their route. */
@Service
public class TripAnomalyService {

    /** A window's report, and the running service day, are recomputed at most this often. */
    static final Duration CACHE_FOR = Duration.ofMinutes(1);
    /** The report lists at most this many abnormal trips (the latest); the counts cover them all. */
    static final int MAX_TRIPS = 2000;

    /** See {@link #recordedFeeds(GtfsProperties)}. */
    private final String[] recordedFeeds;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Each service day's outcomes, and when they were computed; a day long over is kept as it is. */
    private final Map<LocalDate, DayOutcomes> days = new ConcurrentHashMap<>();
    /** The running day's brigades whose trips are all settled, with their outcomes: judged once. */
    private final Map<LocalDate, SettledBrigades> settledBrigades = new ConcurrentHashMap<>();
    /**
     * Bumped whenever the days are forgotten ({@link #forgetOutcomes}) or a timetable is imported: a day judged before
     * that, with the old brigades or trips, isn't kept.
     */
    private final AtomicLong generation = new AtomicLong();
    private final RecentCache<ReportWindow, TripAnomalyDto.Report> reports;
    private volatile Shapes shapes;
    private volatile RunningDayTrips runningDayTrips;
    /** The running day's reports, kept in memory and read in pieces. */
    private final RecentReports recent;
    /** Raised whenever the judging changes: stored days are out of date then. */
    private static final String LOGIC = "1";
    private final DaySummaries summaries;
    private final DaySummaries.Store<Outcome> stored;

    public TripAnomalyService(JdbcTemplate jdbc, Clock clock, GtfsProperties feeds, DaySummaries summaries) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.recordedFeeds = recordedFeeds(feeds);
        this.reports = new RecentCache<>(clock, CACHE_FOR, 16);
        this.recent = new RecentReports(clock, this::readReports, this::readMinutes,
                ServiceDayCache.positionCount(jdbc));
        this.summaries = summaries;
        this.stored = summaries.store("trip-outcomes", this::fingerprint, Outcome.class);
        summaries.onOutdated(days::remove);
    }

    /**
     * Of the learnt brigades on lines. Not of the timetable: a day that is over stays as judged, as imports drop its
     * trips.
     */
    private String fingerprint() {
        return LOGIC + ":" + jdbc.queryForObject("SELECT md5(coalesce(string_agg(x::text, ',' ORDER BY x::text), ''))"
                + " FROM recorded_brigade_line x", String.class);
    }

    /**
     * The feeds whose buses are on the live feed. Other feeds' trips (PKS Nova's line 500) are left out: nothing can be
     * recorded for them, so they would all look cancelled.
     */
    static String[] recordedFeeds(GtfsProperties feeds) {
        return Stream.concat(Stream.of(TimetableFeed.MAIN), feeds.extra().keySet().stream())
                .filter(key -> feeds.feed(key).agency() != null).toArray(String[]::new);
    }

    /** Outcomes by brigade, worked out from one reading of the reports ({@link RecentReports#reading()}). */
    private record SettledBrigades(long reading, Map<String, List<Outcome>> outcomes) {
    }

    private record DayOutcomes(Instant computed, List<Outcome> outcomes) {
    }

    /** A day's trips, for the timetable and brigades ({@link #generation}) they were read with. */
    private record RunningDayTrips(LocalDate day, long generation, String version, List<ScheduledTrip> trips) {
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
        if (!ServiceDay.start(day.plusDays(1)).plus(ServiceDayCache.DAY_SETTLES).isBefore(clock.instant())) {
            return judgeAndKeep(day, now);
        }
        // The day is over: stored, or judged (one day at a time, as it takes seconds), as of now whatever the window.
        return load(day).orElseGet(() -> summaries.compute(
                () -> load(day).orElseGet(() -> judgeAndKeep(day, clock.instant()))));
    }

    /** A day's stored outcomes, kept in memory from now on. */
    private Optional<List<Outcome>> load(LocalDate day) {
        long readIn = generation.get();
        Optional<List<Outcome>> outcomes = stored.load(day);
        outcomes.filter(o -> generation.get() == readIn)
                .ifPresent(o -> days.put(day, new DayOutcomes(clock.instant(), o)));
        return outcomes;
    }

    private List<Outcome> judgeAndKeep(LocalDate day, Instant now) {
        long judgedIn = generation.get();
        long positions = summaries.positions(day);
        List<Outcome> outcomes = judge(day, now);
        if (generation.get() == judgedIn) {
            DayOutcomes judged = new DayOutcomes(now, outcomes);
            days.put(day, judged);
            if (settled(judged, day)) {
                stored.save(day, outcomes, positions);
            }
        }
        LocalDate oldest = ServiceDay.serviceDay(now).minusDays(ServiceDayCache.KEEP_DAYS);
        days.keySet().removeIf(d -> d.isBefore(oldest));
        // Brigades are kept for the running day only (and the day before, while its night trips run).
        settledBrigades.keySet().removeIf(d -> d.isBefore(ServiceDay.serviceDay(now).minusDays(1)));
        return outcomes;
    }

    private List<Outcome> judge(LocalDate day, Instant now) {
        long judgedIn = generation.get();
        List<ScheduledTrip> trips = day.equals(ServiceDay.serviceDay(clock.instant()))
                ? runningDayTrips(day, judgedIn) : trips(day);
        if (trips.isEmpty()) {
            return List.of();
        }
        Instant from = ServiceDay.start(day);
        Instant to = reportsEnd(day, trips);
        Instant until = to.isAfter(now) ? now : to;
        if (recent.covers(from, until)) {
            return judgeOpenBrigades(day, trips, from, until, now, judgedIn);
        }
        List<Report> reports = reports(from, until);
        return TripAnomalies.judge(trips, reports, TripAnomalies.Recording.of(reports), shapes().byId(), now);
    }

    /**
     * The running day's outcomes: the brigades whose trips are all settled were judged once and are kept; the others
     * are judged again, with the reports of the buses that ran them (as judging the whole day would). A
     * brigade's outcomes depend on its buses, and on whether its lines were reported and matched at all that day: both
     * can only become true, so a brigade is kept once all its lines are matched. A brigade with a change of bus is judged again until the day is over, as
     * whether the replaced bus was used again can change later. When the reports are read again whole (some were
     * stored late), every brigade is judged again.
     *
     * @param judgedIn the {@link #generation} before the trips were read: if it has passed, nothing is kept
     */
    private List<Outcome> judgeOpenBrigades(LocalDate day, List<ScheduledTrip> trips, Instant from, Instant until,
                                            Instant now, long judgedIn) {
        long reading = recent.reading();
        Map<String, List<ScheduledTrip>> byBrigade = TripMatching.byBrigade(trips);
        Map<String, List<Outcome>> settled = settledBrigades.compute(day, (d, before) ->
                before != null && before.reading() == reading ? before
                        : new SettledBrigades(reading, new ConcurrentHashMap<>())).outcomes();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> open = new HashSet<>();
        Set<String> linesMatched = new HashSet<>();
        byBrigade.forEach((brigade, its) -> {
            List<Outcome> kept = settled.get(brigade);
            if (kept != null) {
                outcomes.addAll(kept);
                its.forEach(t -> linesMatched.add(t.line()));
            } else {
                open.add(brigade);
            }
        });
        if (open.isEmpty()) {
            return outcomes;
        }
        List<ScheduledTrip> openTrips = trips.stream().filter(t -> open.contains(t.brigade())).toList();
        TripAnomalies.Judged result = TripAnomalies.judge(openTrips, recent.reports(from, until, open),
                new TripAnomalies.Recording(recent.reportMinutes(from, until)), shapes().byId(), now,
                recent.lines(from, until), linesMatched);
        List<Outcome> judged = result.outcomes();
        outcomes.addAll(judged);

        Map<String, String> brigadeOf = new HashMap<>();
        openTrips.forEach(t -> brigadeOf.put(TripAnomalies.key(t), t.brigade()));
        Map<String, List<Outcome>> judgedBy = new HashMap<>();
        for (Outcome o : judged) {
            judgedBy.computeIfAbsent(brigadeOf.get(TripAnomalies.key(o)), k -> new ArrayList<>()).add(o);
        }
        for (String brigade : open) {
            Instant lastArrival = byBrigade.get(brigade).stream().map(ScheduledTrip::arrival)
                    .max(Comparator.naturalOrder()).orElseThrow();
            List<Outcome> its = judgedBy.getOrDefault(brigade, List.of());
            if (lastArrival.plus(TripAnomalies.SETTLE).plus(TripAnomalies.LOST_AFTER).isBefore(now)
                    && byBrigade.get(brigade).stream().allMatch(t -> result.linesMatched().contains(t.line()))
                    && its.stream().noneMatch(o -> o.kind() == TripAnomalies.Kind.VEHICLE_CHANGED)
                    && generation.get() == judgedIn) {
                settled.put(brigade, List.copyOf(its));
            }
        }
        return outcomes;
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
        return new TripAnomalies.Recording(
                recent.covers(from, to) ? recent.minutes(from, to) : readMinutes(from, to));
    }

    /** The minutes (epoch / 60) with any report in {@code [from, to)}, read from the database. */
    private Set<Long> readMinutes(Instant from, Instant to) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT (extract(epoch FROM observed_at) / 60)::bigint FROM position_sample
                WHERE observed_at >= ? AND observed_at < ?""", Long.class, Timestamp.from(from), Timestamp.from(to)));
    }

    /** Drops the days judged so far: the brigades changed ({@link RecordedBrigades}), so the days are judged again. */
    void forgetOutcomes() {
        generation.incrementAndGet();
        days.clear();
        settledBrigades.clear();
        recent.forget();
    }

    /** After a feed's import: reports are read on the timetable's lines, which may have changed. */
    @EventListener
    public void afterImport(GtfsImported imported) {
        generation.incrementAndGet();
        settledBrigades.clear();
        recent.forget();
    }

    private boolean fresh(DayOutcomes cached, LocalDate day, Instant now) {
        return settled(cached, day) || cached.computed().plus(CACHE_FOR).isAfter(now);
    }

    /** Whether all the day's trips were settled when it was judged. */
    private static boolean settled(DayOutcomes cached, LocalDate day) {
        // Night lines run past the next day's start: the day is settled once its last trip is.
        Instant lastArrival = cached.outcomes().stream().map(Outcome::arrival).max(Comparator.naturalOrder())
                .filter(a -> a.isAfter(ServiceDay.start(day.plusDays(1))))
                .orElse(ServiceDay.start(day.plusDays(1)));
        Instant settled = lastArrival.plus(TripAnomalies.SETTLE).plus(TripAnomalies.LOST_AFTER);
        return cached.computed().isAfter(settled);
    }

    /** The running day's trips, as {@link #trips(LocalDate)}: kept until the timetable or the brigades change. */
    private List<ScheduledTrip> runningDayTrips(LocalDate day, long generationNow) {
        String version = shapes().version();
        RunningDayTrips kept = runningDayTrips;
        if (kept == null || !kept.day().equals(day) || kept.generation() != generationNow
                || !kept.version().equals(version)) {
            kept = new RunningDayTrips(day, generationNow, version, trips(day));
            runningDayTrips = kept;
        }
        return kept.trips();
    }

    /** The trips of the timetable services running that day, with their stops. */
    private List<ScheduledTrip> trips(LocalDate day) {
        return trips(day, null);
    }

    /** As {@link #trips(LocalDate)}, of one brigade only (null: all). */
    List<ScheduledTrip> trips(LocalDate day, String brigade) {
        return trips(day, recordedFeeds, brigade);
    }

    /** The trips of some feeds running that day, of one brigade only (null: all). */
    List<ScheduledTrip> trips(LocalDate day, String[] feeds, String brigade) {
        record Row(long trip, String tripId, String line, String brigade, String headsign, String shapeId, int seq,
                   String stop, int departure, int arrival, double lat, double lon) {
        }
        List<Row> rows = jdbc.query("""
                SELECT t.id, t.gtfs_id, r.gtfs_id AS line, %s AS brigade, t.headsign, %s AS shape_id, st.stop_sequence,
                       s.name, s.latitude, s.longitude, extract(epoch FROM st.departure_time::interval)::int AS departure,
                       extract(epoch FROM st.arrival_time::interval)::int AS arrival
                FROM trip t JOIN route r ON r.id = t.route_id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                WHERE t.feed = ANY (?) AND %s%s
                ORDER BY t.id, st.stop_sequence""".formatted(ServiceDay.BRIGADE_SQL, shapeKey("t"),
                        ServiceDay.TRIPS_OF_DAY_SQL,
                        brigade == null ? "" : " AND " + ServiceDay.BRIGADE_SQL + " = ?"),
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("gtfs_id"), rs.getString("line"),
                        rs.getString("brigade"), rs.getString("headsign"), rs.getString("shape_id"),
                        rs.getInt("stop_sequence"), StopPlaces.stopName(rs.getString("name")),
                        rs.getInt("departure"), rs.getInt("arrival"), rs.getDouble("latitude"),
                        rs.getDouble("longitude")),
                Stream.concat(Stream.<Object>of((Object) feeds), Arrays.stream(brigade == null
                        ? ServiceDay.tripsOfDayArgs(day) : new Object[] {day, day.plusDays(1), brigade})).toArray());
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

    /**
     * Reports on the timetable's lines in {@code [from, to)}, by vehicle and time, each with the brigade it belongs to
     * ({@link RecordedBrigades#BRIGADE_SQL}).
     */
    private List<Report> reports(Instant from, Instant to) {
        return recent.covers(from, to) ? recent.reports(from, to) : readReports(from, to);
    }

    /** As {@link #reports(Instant, Instant)}, read from the database. */
    private List<Report> readReports(Instant from, Instant to) {
        return reports(RecordedBrigades.BRIGADE_SQL, "TRUE", Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * As {@link #reports(Instant, Instant)}, of the buses that ran a brigade then (any of their reports, also on
     * other brigades), or of all buses (null).
     */
    List<Report> reports(Instant from, Instant to, String brigade) {
        return brigade == null ? reports(from, to) : reports(from, to, List.of(brigade));
    }

    /** As {@link #reports(Instant, Instant, String)}, of the buses that ran any of some brigades then. */
    List<Report> reports(Instant from, Instant to, Collection<String> brigades) {
        if (recent.covers(from, to)) {
            return recent.reports(from, to, brigades);
        }
        return reports(RecordedBrigades.BRIGADE_SQL, """
                p.vehicle_id IN (SELECT p.vehicle_id FROM %s
                                 WHERE p.observed_at >= ? AND p.observed_at < ? AND %s = ANY (?))""".formatted(
                        RecordedBrigades.REPORTS_FROM, RecordedBrigades.BRIGADE_SQL),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                brigades.toArray(String[]::new));
    }

    /** One operator's reports in {@code [from, to)} with the brigades as the live feed names them, by vehicle and time. */
    List<Report> liveReports(Instant from, Instant to, String agency) {
        return reports("p.brigade", "v.agency = ?", Timestamp.from(from), Timestamp.from(to), agency);
    }

    /**
     * Reports on the timetable's lines in a time window (the first two arguments), meeting a condition, by vehicle
     * and time.
     *
     * @param brigade SQL of a report's brigade, on {@link RecordedBrigades#REPORTS_FROM}
     */
    private List<Report> reports(String brigade, String condition, Object... args) {
        // Filtering on observed_at alone (the hypertable's time column) gives the planner good estimates.
        return jdbc.query("""
                SELECT p.vehicle_id, v.vehicle_number, p.observed_at, p.route_name, %s AS brigade, p.headsign,
                       p.delay_seconds, p.next_stop_sequence, p.latitude, p.longitude
                FROM %s
                WHERE p.observed_at >= ? AND p.observed_at < ? AND p.route_name IN (SELECT gtfs_id FROM route)
                  AND %s
                ORDER BY p.vehicle_id, p.observed_at""".formatted(brigade, RecordedBrigades.REPORTS_FROM, condition),
                (rs, i) -> new Report(rs.getLong("vehicle_id"), rs.getString("vehicle_number"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getString("route_name"),
                        rs.getString("brigade"), rs.getString("headsign"),
                        (Integer) rs.getObject("delay_seconds"), rs.getObject("next_stop_sequence", Integer.class),
                        rs.getDouble("latitude"), rs.getDouble("longitude")),
                args);
    }

    /**
     * SQL: the key of a trip's or shape point's shape among all feeds' (feeds reuse each other's shape ids), on a
     * table alias with {@code feed} and {@code shape_id}; null when the trip has no shape.
     */
    private static String shapeKey(String alias) {
        return alias + ".feed || '/' || " + alias + ".shape_id";
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
        jdbc.query("SELECT " + shapeKey("sp") + " AS shape_id, latitude, longitude FROM shape_point sp"
                        + " ORDER BY feed, shape_id, sequence",
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
