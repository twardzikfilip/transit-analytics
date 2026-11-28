package com.transitanalytics.trips;

import com.transitanalytics.data.Reports;
import com.transitanalytics.data.Shapes;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.brigades.RecordedBrigades;
import com.transitanalytics.common.DaySummaries;
import com.transitanalytics.common.RecentCache;
import com.transitanalytics.data.RecentReports;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.common.ServiceDayCache;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.timetable.TimetableMath;
import com.transitanalytics.trips.TripAnomalies.Outcome;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import com.transitanalytics.trips.TripShapes.Shape;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Abnormal trips: scheduled trips not run, run only partly, lost, or off their route. */
@Service
public class TripAnomalyService {

    /** A window's report, and the running service day, are recomputed at most this often. */
    static final Duration CACHE_FOR = Duration.ofMinutes(1);
    /** The report lists at most this many abnormal trips (the latest); the counts cover them all. */
    static final int MAX_TRIPS = 2000;

    private final Timetables timetables;
    private final Reports reports;
    private final Shapes shapeReader;
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
    private final RecentCache<ReportWindow, TripAnomalyDto.Report> windows;
    private volatile BusShapes shapes;
    private volatile RunningDayTrips runningDayTrips;
    /** Raised whenever the judging changes: stored days are out of date then. */
    private static final String LOGIC = "1";
    private final DaySummaries summaries;
    private final DaySummaries.Store<Outcome> stored;

    public TripAnomalyService(Timetables timetables, Reports reports, Shapes shapes, Clock clock,
                              DaySummaries summaries) {
        this.timetables = timetables;
        this.reports = reports;
        this.shapeReader = shapes;
        this.clock = clock;
        this.windows = new RecentCache<>(clock, CACHE_FOR, 16);
        this.summaries = summaries;
        this.stored = summaries.keeping("trip-outcomes", () -> LOGIC, Outcome.class, TripAnomalies::key)
                .upgrading(AnomalyNotes::upgrade);
        summaries.onOutdated("trip-outcomes", days::remove);
    }

    /** Outcomes by brigade, worked out from one reading of the reports ({@link RecentReports#reading()}). */
    private record SettledBrigades(long reading, Map<String, List<Outcome>> outcomes) {
    }

    private record DayOutcomes(Instant computed, List<Outcome> outcomes) {
    }

    /** A day's trips, for the timetable and brigades ({@link #generation}) they were read with. */
    private record RunningDayTrips(LocalDate day, long generation, String version, List<ScheduledTrip> trips) {
    }

    /** Bus route shapes, for the timetable they were read from. */
    private record BusShapes(String version, Map<String, Shape> byId) {
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
        return windows.get(window, () -> report(window.from(), window.to(), clock.instant()));
    }

    /**
     * The outcomes of the trips scheduled to run (for any part) in {@code [from, to)}, judged as of now (a window
     * reaching into the future leaves its trips pending).
     */
    public List<Outcome> outcomes(Instant from, Instant to) {
        return outcomes(from, to, clock.instant());
    }

    /** As {@link #outcomes(Instant, Instant)}, judged as of {@code now}. */
    public List<Outcome> outcomes(Instant from, Instant to, Instant now) {
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
                        .thenComparing(TripAnomalyDto.Trip::route, TimetableMath.ROUTE_ORDER))
                .toList();
        return new TripAnomalyDto.Report(from, to, TripSummaries.summary(outcomes),
                TripSummaries.routes(outcomes), abnormal.size(),
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
        List<Outcome> outcomes = load(day).orElseGet(() -> summaries.compute(
                () -> load(day).orElseGet(() -> judgeAndKeep(day, clock.instant()))));
        WarmUp.pauseNow();
        return outcomes;
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
        // Counted only for a day that can be stored.
        long positions = now.isAfter(ServiceDay.start(day.plusDays(1))) ? summaries.positions(day) : 0;
        List<Outcome> outcomes = judge(day, now);
        if (generation.get() == judgedIn) {
            DayOutcomes judged = new DayOutcomes(now, outcomes);
            if (settled(judged, day)) {
                outcomes = stored.save(day, outcomes, positions);
                judged = new DayOutcomes(now, outcomes);
            }
            days.put(day, judged);
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
                ? runningDayTrips(day, judgedIn) : timetables.trips(day, null);
        if (trips.isEmpty()) {
            return List.of();
        }
        Instant from = ServiceDay.start(day);
        Instant to = reportsEnd(day, trips);
        Instant until = to.isAfter(now) ? now : to;
        Optional<List<Outcome>> pieces = judgeOpenBrigades(day, trips, from, until, now, judgedIn);
        if (pieces.isPresent()) {
            return pieces.get();
        }
        List<Report> read = reports.of(from, until);
        return TripAnomalies.judge(trips, read, TripAnomalies.Recording.of(read), shapes().byId(), now);
    }

    /**
     * The running day's outcomes: the brigades whose trips are all settled were judged once and are kept; the others
     * are judged again, with the reports of the buses that ran them (as judging the whole day would). A
     * brigade's outcomes depend on its buses, and on whether its routes were reported and matched at all that day: both
     * can only become true, so a brigade is kept once all its routes are matched. A brigade with a change of bus is
     * judged again until the day is over, as whether the replaced bus was used again can change later. When the reports are read again whole (some were
     * stored late), every brigade is judged again. Empty when the day's reports aren't kept in memory (any more).
     *
     * @param judgedIn the {@link #generation} before the trips were read: if it has passed, nothing is kept
     */
    private Optional<List<Outcome>> judgeOpenBrigades(LocalDate day, List<ScheduledTrip> trips, Instant from,
                                                      Instant until, Instant now, long judgedIn) {
        Map<String, List<ScheduledTrip>> byBrigade = TripMatching.byBrigade(trips);
        return reports.recent().read(from, until, () -> openBrigades(day, byBrigade, from, until))
                .map(open -> judge(open, trips, byBrigade, now, judgedIn));
    }

    /** What the running day's judging starts from: the brigades settled before, and the others' reports. */
    private record OpenBrigades(Map<String, List<Outcome>> settled, Set<String> open, List<Report> reports,
                                Set<Long> minutes, Set<String> routes) {
    }

    private OpenBrigades openBrigades(LocalDate day, Map<String, List<ScheduledTrip>> byBrigade, Instant from,
                                      Instant until) {
        RecentReports recent = reports.recent();
        long reading = recent.reading();
        Map<String, List<Outcome>> settled = settledBrigades.compute(day, (d, before) ->
                before != null && before.reading() == reading ? before
                        : new SettledBrigades(reading, new ConcurrentHashMap<>())).outcomes();
        Set<String> open = new HashSet<>(byBrigade.keySet());
        open.removeAll(settled.keySet());
        return new OpenBrigades(settled, open, recent.reports(from, until, open), recent.reportMinutes(from, until),
                recent.routes(from, until));
    }

    private List<Outcome> judge(OpenBrigades day, List<ScheduledTrip> trips,
                                Map<String, List<ScheduledTrip>> byBrigade, Instant now, long judgedIn) {
        Map<String, List<Outcome>> settled = day.settled();
        Set<String> open = day.open();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> routesMatched = new HashSet<>();
        byBrigade.forEach((brigade, its) -> {
            if (!open.contains(brigade)) {
                outcomes.addAll(settled.get(brigade));
                its.forEach(t -> routesMatched.add(t.route()));
            }
        });
        if (open.isEmpty()) {
            return outcomes;
        }
        List<ScheduledTrip> openTrips = trips.stream().filter(t -> open.contains(t.brigade())).toList();
        TripAnomalies.Judged result = TripAnomalies.judge(openTrips, day.reports(),
                new TripAnomalies.Recording(day.minutes()), shapes().byId(), now, day.routes(), routesMatched);
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
                    && byBrigade.get(brigade).stream().allMatch(t -> result.routesMatched().contains(t.route()))
                    && its.stream().noneMatch(o -> o.kind() == TripAnomalies.Kind.VEHICLE_CHANGED)
                    && generation.get() == judgedIn) {
                settled.put(brigade, List.copyOf(its));
            }
        }
        return outcomes;
    }

    /**
     * Until when reports count for a service day's trips: the next day's start, or later when trips run past it (the
     * night routes run until about 04:00), with time for late buses to finish.
     */
    public static Instant reportsEnd(LocalDate day, List<ScheduledTrip> trips) {
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
        return new TripAnomalies.Recording(reports.minutes(from, to));
    }

    /** Drops the days judged so far: the brigades changed ({@link RecordedBrigades}), so the days are judged again. */
    public void forgetOutcomes() {
        generation.incrementAndGet();
        days.clear();
        settledBrigades.clear();
        reports.forget();
    }

    /** After a feed's import: reports are read on the timetable's routes, which may have changed. */
    @EventListener
    public void afterImport(GtfsImported imported) {
        if (TimetableFeed.RAIL.equals(imported.feed())) {
            return;
        }
        generation.incrementAndGet();
        settledBrigades.clear();
        reports.forget();
    }

    private boolean fresh(DayOutcomes cached, LocalDate day, Instant now) {
        return settled(cached, day) || cached.computed().plus(CACHE_FOR).isAfter(now);
    }

    /** Whether all the day's trips were settled when it was judged. */
    private static boolean settled(DayOutcomes cached, LocalDate day) {
        // Night routes run past the next day's start: the day is settled once its last trip is.
        Instant lastArrival = cached.outcomes().stream().map(Outcome::arrival).max(Comparator.naturalOrder())
                .filter(a -> a.isAfter(ServiceDay.start(day.plusDays(1))))
                .orElse(ServiceDay.start(day.plusDays(1)));
        Instant settled = lastArrival.plus(TripAnomalies.SETTLE).plus(TripAnomalies.LOST_AFTER);
        return cached.computed().isAfter(settled);
    }

    /** The running day's trips ({@link Timetables#trips}): kept until the timetable or the brigades change. */
    private List<ScheduledTrip> runningDayTrips(LocalDate day, long generationNow) {
        String version = shapes().version();
        RunningDayTrips kept = runningDayTrips;
        if (kept == null || !kept.day().equals(day) || kept.generation() != generationNow
                || !kept.version().equals(version)) {
            kept = new RunningDayTrips(day, generationNow, version, timetables.trips(day, null));
            runningDayTrips = kept;
        }
        return kept.trips();
    }

    /** All bus routes' shapes, read again when the timetable changes (another import). */
    private BusShapes shapes() {
        String version = shapeReader.busesVersion();
        BusShapes current = shapes;
        if (current != null && current.version().equals(version)) {
            return current;
        }
        Map<String, Shape> byId = new HashMap<>();
        shapeReader.buses().forEach((id, points) -> byId.put(id, TripShapes.shape(points)));
        BusShapes fresh = new BusShapes(version, byId);
        shapes = fresh;
        return fresh;
    }
}
