package com.transitanalytics.trips;

import com.transitanalytics.data.Reports;
import com.transitanalytics.data.Shapes;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.brigades.BrigadeDayService;
import com.transitanalytics.common.NotFoundException;
import com.transitanalytics.common.RecentCache;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.dto.TripRunDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.trips.TripAnomalies.Outcome;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import org.springframework.stereotype.Service;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.transitanalytics.timetable.TimetableMath.ROUTE_ORDER;
import static com.transitanalytics.trips.TripAnomalies.MATCH_TOLERANCE;

/**
 * The trips scheduled in a time window, each with its outcome (as on Abnormal trips) and what ran: the bus, its
 * delay and speed along the way, and where it stood still. Searchable by bus, brigade, route or destination.
 */
@Service
public class TripRunService {

    /** Trips listed a page. */
    static final int PAGE_SIZE = 50;
    /** A sample of a bus's delay and speed at most this often: its speed is the average since the one before. */
    static final Duration SAMPLE_EVERY = Duration.ofSeconds(60);
    /** A bus within this distance of where it stopped is standing still… */
    static final double STAND_METERS = 40;
    /** …and a stand this long is shown. */
    static final int STAND_MINUTES = 3;
    /** A bus this close to the trip's last stop has arrived: standing there is the end of the trip. */
    static final double AT_END_METERS = 150;
    /** Faster than this between two samples is a GPS jump. */
    static final int MAX_KMH = 100;

    private final Timetables timetables;
    private final Reports reports;
    private final Shapes shapes;
    private final TripAnomalyService anomalies;
    private final Clock clock;
    /** Each service day's scheduled trips: the timetable changes only on an import. */
    private final RecentCache<LocalDate, List<ScheduledTrip>> dayTrips;

    public TripRunService(Timetables timetables, Reports reports, Shapes shapes, TripAnomalyService anomalies,
                          Clock clock) {
        this.timetables = timetables;
        this.reports = reports;
        this.shapes = shapes;
        this.anomalies = anomalies;
        this.clock = clock;
        this.dayTrips = new RecentCache<>(clock, Duration.ofMinutes(10), 4);
    }

    /**
     * A page of the trips scheduled to run (for any part) in the window, in departure order; of a window longer than
     * {@link TripRunDto.Page#MAX_HOURS}, its last hours.
     *
     * @param q a bus's number, a brigade or a route (exactly), or part of a destination; null or blank: all
     */
    public TripRunDto.Page page(ReportWindow window, String q, int page) {
        Instant to = window.to();
        Instant from = window.length().compareTo(Duration.ofHours(TripRunDto.Page.MAX_HOURS)) > 0
                ? to.minus(Duration.ofHours(TripRunDto.Page.MAX_HOURS)) : window.from();
        String query = q == null || q.isBlank() ? null : q.trim();
        Instant now = clock.instant();

        Map<LocalDate, List<ScheduledTrip>> days = new LinkedHashMap<>();
        for (LocalDate day = ServiceDay.serviceDay(from).minusDays(1); !day.isAfter(ServiceDay.serviceDay(to));
             day = day.plusDays(1)) {
            LocalDate d = day;
            days.put(day, dayTrips.get(day, () -> timetables.trips(d, null)));
        }
        // Trips of the brigades the bus ran then are candidates: whether it ran them shows once they are matched.
        Set<String> busBrigades = query == null ? Set.of()
                : reports.brigadesOf(query, from.minus(MATCH_TOLERANCE), to.plus(MATCH_TOLERANCE));
        List<Candidate> candidates = new ArrayList<>();
        days.forEach((day, trips) -> {
            for (ScheduledTrip trip : trips) {
                if (trip.departure().isBefore(to) && !trip.arrival().isBefore(from)) {
                    boolean named = query == null || named(trip, query);
                    if (named || busBrigades.contains(trip.brigade())) {
                        candidates.add(new Candidate(day, trip, named));
                    }
                }
            }
        });
        candidates.sort(Comparator.comparing((Candidate c) -> c.trip().departure())
                .thenComparing(c -> c.trip().route(), ROUTE_ORDER).thenComparing(c -> c.trip().brigade()));

        // Trips only the bus could have run: kept when it did (a handful, so they are worked out first).
        List<Candidate> byBus = candidates.stream().filter(c -> !c.named()).toList();
        Map<Candidate, TripRunDto.Trip> ran = new HashMap<>();
        build(byBus, days, now).forEach((c, trip) -> {
            if (trip.trip().runs().stream().anyMatch(r -> r.bus().equalsIgnoreCase(query))) {
                ran.put(c, trip);
            }
        });
        List<Candidate> listed = candidates.stream().filter(c -> c.named() || ran.containsKey(c)).toList();

        int first = (int) Math.min((long) page * PAGE_SIZE, listed.size());
        List<Candidate> shown = listed.subList(first, Math.min(first + PAGE_SIZE, listed.size()));
        Map<Candidate, TripRunDto.Trip> built = new HashMap<>(ran);
        built.putAll(build(shown.stream().filter(c -> !ran.containsKey(c)).toList(), days, now));
        return new TripRunDto.Page(window.from(), to, from, query, listed.size(), page, PAGE_SIZE,
                shown.stream().map(built::get).toList());
    }

    /**
     * A brigade's trips on a service day, as {@link #page} gives them.
     *
     * @param day null: today's service day
     */
    public TripRunDto.Day day(LocalDate day, String brigade) {
        Instant now = clock.instant();
        LocalDate serviceDay = day == null ? ServiceDay.serviceDay(now) : day;
        List<ScheduledTrip> trips = timetables.trips(serviceDay, brigade);
        List<Candidate> candidates = trips.stream().sorted(Comparator.comparing(ScheduledTrip::departure))
                .map(trip -> new Candidate(serviceDay, trip, true)).toList();
        Map<Candidate, TripRunDto.Trip> built = build(candidates, Map.of(serviceDay, trips), now);
        List<TripRunDto.Trip> runs = candidates.stream().map(built::get).toList();
        return new TripRunDto.Day(serviceDay, brigade,
                BrigadeDayService.summary(runs.stream().map(TripRunDto.Trip::trip).toList()), runs);
    }

    private record Candidate(LocalDate day, ScheduledTrip trip, boolean named) {
    }

    /** A bus that moved less than this between two reports (and under {@link #STANDING_MPS}) stood still… */
    static final double STANDING_METRES = 5;
    /** …or less than this many metres a second over a longer interval: GPS jitter, not driving. */
    static final double STANDING_MPS = 0.4;
    /** A moving bus's speed is the average over its reports this far either side. */
    static final Duration SMOOTH = Duration.ofSeconds(15);
    /** Reports further apart than this aren't joined: where the bus went in between is unknown. */
    static final Duration REPORT_GAP = Duration.ofMinutes(2);

    /**
     * One trip in detail: every report of its buses while they ran it, with how late and how fast they were; its
     * route; and its stops, with how long a bus stood at each. The brigade's day so far is matched as on its day page.
     *
     * @throws NotFoundException when the brigade has no such trip that day
     */
    public TripRunDto.Detail detail(LocalDate day, String brigade, String tripId) {
        List<ScheduledTrip> trips = timetables.trips(day, brigade);
        ScheduledTrip trip = trips.stream().filter(t -> t.tripId().equals(tripId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Trip", tripId + " of " + brigade + " on " + day));
        Instant now = clock.instant();
        Instant start = ServiceDay.start(day);
        Instant end = TripAnomalyService.reportsEnd(day, trips);
        if (end.isAfter(now)) {
            end = now;
        }
        List<Report> reports = start.isBefore(end) ? this.reports.ofBrigades(start, end, List.of(brigade)) : List.of();
        Map<ScheduledTrip, List<Report>> matched = TripMatching.matchReports(TripMatching.byBrigade(trips),
                TripMatching.withBrigades(reports));
        List<Report> on = matched.getOrDefault(trip, List.of()).stream()
                .sorted(Comparator.comparing(Report::time)).toList();
        List<BrigadeDto.ActualRun> runs = BrigadeDayService.runs(trip, on);
        List<Report> running = running(on, runs);
        List<Segment> segments = segments(running);
        List<StopTime> times = stopTimes(trip, running, runs);
        Integer[] kmh = speeds(running, segments);
        List<TripRunDto.DetailPoint> points = new ArrayList<>();
        for (int i = 0; i < running.size(); i++) {
            Report r = running.get(i);
            points.add(new TripRunDto.DetailPoint(r.time(), r.vehicle(), r.lat(), r.lon(), r.usableDelay(), kmh[i]));
        }
        List<double[]> route = shapes.ofTrip(trip.route(), tripId);
        List<Timetables.Stop> planned = timetables.stops(trip.route(), tripId);
        List<TripRunDto.DetailStop> stops = new ArrayList<>();
        for (int i = 0; i < planned.size(); i++) {
            Timetables.Stop stop = planned.get(i);
            double[] at = {stop.lat(), stop.lon()};
            StopTime time = i < times.size() ? times.get(i) : new StopTime(null, null, null);
            stops.add(new TripRunDto.DetailStop(i + 1, stop.name(), at[0], at[1], time.scheduled(), time.passed(),
                    time.delay(), dwell(running, segments, at)));
        }
        return new TripRunDto.Detail(tripId, points, route, stops);
    }

    /**
     * The way between two consecutive reports of the same bus (by their positions in the list), how long it took,
     * and whether the bus stood still; {@code valid} false unless the two are a pair ({@link Reports#paired}) at most
     * {@link #REPORT_GAP} apart, without a GPS jump.
     */
    public record Segment(int from, int to, double metres, double seconds, boolean standing, boolean valid) {
    }

    /** The segments between each bus's consecutive reports (the reports in time order, buses one after the other). */
    static List<Segment> segments(List<Report> reports) {
        List<Segment> segments = new ArrayList<>();
        Map<Long, Integer> last = new HashMap<>();
        for (int i = 0; i < reports.size(); i++) {
            Report r = reports.get(i);
            Integer before = last.put(r.vehicleId(), i);
            if (before == null) {
                continue;
            }
            Report a = reports.get(before);
            double seconds = Duration.between(a.time(), r.time()).toMillis() / 1000.0;
            double metres = TripHandovers.metres(a, r);
            boolean valid = Reports.paired(a, r) && Dwell.usable(metres, seconds);
            boolean standing = metres < Math.max(STANDING_METRES, STANDING_MPS * seconds);
            segments.add(new Segment(before, i, metres, seconds, standing, valid));
        }
        return segments;
    }

    /**
     * Each report's speed: 0 when the bus stood still since its report before; else its average over the moving
     * segments within {@link #SMOOTH} either side (so a jittery GPS doesn't zigzag); null for a bus's first report,
     * after a gap, or a GPS jump.
     */
    static Integer[] speeds(List<Report> reports, List<Segment> segments) {
        Integer[] kmh = new Integer[reports.size()];
        for (Segment s : segments) {
            if (!s.valid()) {
                continue;
            }
            if (s.standing()) {
                kmh[s.to()] = 0;
                continue;
            }
            Instant at = reports.get(s.to()).time();
            long bus = reports.get(s.to()).vehicleId();
            double metres = 0;
            double seconds = 0;
            for (Segment o : segments) {
                Report end = reports.get(o.to());
                if (o.valid() && !o.standing() && end.vehicleId() == bus
                        && Duration.between(at, end.time()).abs().compareTo(SMOOTH) <= 0) {
                    metres += o.metres();
                    seconds += o.seconds();
                }
            }
            kmh[s.to()] = (int) Math.round(metres / seconds * 3.6);
        }
        return kmh;
    }

    /** How long a bus halted near a place ({lat, lon}), from its usable steps there ({@link Dwell}). */
    static Integer dwell(List<Report> reports, List<Segment> segments, double[] place) {
        Dwell.Visit visit = new Dwell.Visit();
        for (Segment s : segments) {
            Report a = reports.get(s.from());
            Report b = reports.get(s.to());
            boolean aNear = Geo.meters(new double[] {a.lat(), a.lon()}, place) <= Dwell.AT_STOP_METRES;
            boolean bNear = Geo.meters(new double[] {b.lat(), b.lon()}, place) <= Dwell.AT_STOP_METRES;
            if (s.valid() && (aNear || bNear)) {
                visit.add(s.metres(), s.seconds());
            }
        }
        return visit.seconds();
    }

    /** A gap in a bus's reports longer than this isn't counted in its average speed: where it went is unknown. */
    static final Duration SPEED_GAP = Duration.ofMinutes(3);

    /**
     * The buses' average speed while they ran the trip: the way between each bus's consecutive reports over the time
     * between them, stops and waits included. Gaps of {@link #SPEED_GAP} and GPS jumps (over {@link #MAX_KMH}) are
     * left out; null when there is no such pair.
     */
    static Integer averageKmh(List<Report> running) {
        double metres = 0;
        long millis = 0;
        Map<Long, Report> previous = new HashMap<>();
        for (Report r : running) {
            Report before = previous.put(r.vehicleId(), r);
            if (before == null) {
                continue;
            }
            Duration dt = Duration.between(before.time(), r.time());
            double m = TripHandovers.metres(before, r);
            if (dt.isZero() || dt.compareTo(SPEED_GAP) > 0 || m / dt.toMillis() * 3600 > MAX_KMH) {
                continue;
            }
            metres += m;
            millis += dt.toMillis();
        }
        return millis == 0 ? null : (int) Math.round(metres / millis * 3600);
    }

    /**
     * Each stop of the trip, when it was due and when a bus passed it: its first report heading beyond the stop (the
     * last stop: the last bus's arrival, when it got there).
     */
    /**
     * When a bus was due to leave a stop of the trip, when one was first seen heading beyond it (for the last stop:
     * the last bus's arrival, when it got there; null when no bus was seen past it), and the feed's delay then.
     */
    record StopTime(Instant scheduled, Instant passed, Integer delay) {
    }

    /** Each stop's times, in the trip's order. */
    static List<StopTime> stopTimes(ScheduledTrip trip, List<Report> running, List<BrigadeDto.ActualRun> runs) {
        List<StopTime> stops = new ArrayList<>();
        BrigadeDto.ActualRun last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        for (int i = 0; i < trip.size(); i++) {
            int index = i;
            Report past = running.stream().filter(r -> r.nextSeq() != null && trip.index(r.nextSeq()) > index)
                    .findFirst().orElse(null);
            Instant passed = past != null ? past.time()
                    : i == trip.size() - 1 && last != null && last.toStop() == trip.size() ? last.arrival() : null;
            Integer delay = past != null ? past.usableDelay()
                    : passed != null ? last.endDelay() : null;
            stops.add(new StopTime(trip.departure().plusSeconds(trip.offsets()[i]), passed, delay));
        }
        return stops;
    }

    /** Whether the search names the trip: its brigade or route, or part of its destination. */
    static boolean named(ScheduledTrip trip, String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        String to = trip.stops()[trip.size() - 1];
        return lower.equalsIgnoreCase(Objects.toString(trip.brigade(), ""))
                || lower.equalsIgnoreCase(trip.route())
                || lower.length() >= 2 && (contains(trip.headsign(), lower) || contains(to, lower));
    }

    private static boolean contains(String text, String lower) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lower);
    }

    /**
     * What ran on some trips: their days' reports around them, matched to all of each day's trips. Their outcomes are
     * the ones Abnormal trips shows when it has the day current; else these trips judged on their own, from the same
     * reports (judging the whole day takes a while).
     */
    private Map<Candidate, TripRunDto.Trip> build(List<Candidate> candidates, Map<LocalDate, List<ScheduledTrip>> days,
                                                 Instant now) {
        Map<Candidate, TripRunDto.Trip> built = new HashMap<>();
        Map<LocalDate, List<Candidate>> byDay = new LinkedHashMap<>();
        candidates.forEach(c -> byDay.computeIfAbsent(c.day(), k -> new ArrayList<>()).add(c));
        byDay.forEach((day, wanted) -> {
            // The day so far of the buses on these trips' brigades: what they did before and after tells their
            // outcomes (a trip not run while the brigade ran others is missed, not cancelled).
            Set<String> brigades = new HashSet<>();
            wanted.forEach(c -> brigades.add(c.trip().brigade()));
            List<ScheduledTrip> trips = days.get(day).stream().filter(t -> brigades.contains(t.brigade())).toList();
            Instant start = ServiceDay.start(day);
            Instant end = TripAnomalyService.reportsEnd(day, trips);
            if (end.isAfter(now)) {
                end = now;
            }
            List<Report> reports = start.isBefore(end) ? this.reports.ofBrigades(start, end, brigades) : List.of();
            Map<String, List<ScheduledTrip>> byBrigade = TripMatching.byBrigade(trips);
            Map<ScheduledTrip, List<Report>> matched = TripMatching.matchReports(byBrigade,
                    TripMatching.withBrigades(reports));
            Map<String, Outcome> outcomes = new HashMap<>();
            for (Outcome o : anomalies.currentOutcomes(day).orElseGet(() -> anomalies.judge(trips, reports, day, now))) {
                outcomes.put(TripAnomalies.key(o), o);
            }
            for (Candidate c : wanted) {
                ScheduledTrip trip = c.trip();
                List<Report> on = matched.getOrDefault(trip, List.of()).stream()
                        .sorted(Comparator.comparing(Report::time)).toList();
                BrigadeDto.Trip run = BrigadeDayService.trip(trip,
                        outcomes.get(TripAnomalies.key(trip)), on,
                        byBrigade.get(trip.brigade()).get(0));
                List<Report> running = running(on, run.runs());
                built.put(c, new TripRunDto.Trip(day, trip.brigade(), run, samples(trip, running),
                        stands(trip, running), averageKmh(running)));
            }
        });
        return built;
    }

    /**
     * The reports of each bus while it ran its part of the trip: not those of a late bus still finishing its trip
     * before, nor waiting to leave.
     */
    static List<Report> running(List<Report> reports, List<BrigadeDto.ActualRun> runs) {
        return reports.stream().filter(r -> runs.stream().anyMatch(run -> run.bus().equals(r.vehicle())
                && !r.time().isBefore(run.departure()) && !r.time().isAfter(run.arrival()))).toList();
    }

    /** A sample at most every {@link #SAMPLE_EVERY} per bus, with its speed along the path since the one before. */
    static List<TripRunDto.Sample> samples(ScheduledTrip trip, List<Report> reports) {
        List<TripRunDto.Sample> samples = new ArrayList<>();
        Map<Long, Report> previous = new HashMap<>();
        Map<Long, Report> kept = new HashMap<>();
        Map<Long, Double> metres = new HashMap<>();
        for (Report r : reports) {
            if (r.nextSeq() == null) {
                continue;
            }
            Report before = previous.put(r.vehicleId(), r);
            double path = metres.getOrDefault(r.vehicleId(), 0.0) + (before == null ? 0 : TripHandovers.metres(before, r));
            Report last = kept.get(r.vehicleId());
            if (last != null && Duration.between(last.time(), r.time()).compareTo(SAMPLE_EVERY) < 0) {
                metres.put(r.vehicleId(), path);
                continue;
            }
            Integer kmh = null;
            if (last != null) {
                double hours = Duration.between(last.time(), r.time()).toMillis() / 3_600_000.0;
                long speed = Math.round(path / 1000 / hours);
                kmh = speed > MAX_KMH ? null : (int) speed;
            }
            samples.add(new TripRunDto.Sample(r.time(), r.vehicle(), r.usableDelay(), kmh,
                    trip.index(r.nextSeq()) + 1));
            kept.put(r.vehicleId(), r);
            metres.put(r.vehicleId(), 0.0);
        }
        return samples;
    }

    /**
     * Where a bus stood still for {@link #STAND_MINUTES} or more on the way: its reports staying within
     * {@link #STAND_METERS} of the first. Waiting before the first stop, and at the last, is the timetable's.
     */
    static List<TripRunDto.Stand> stands(ScheduledTrip trip, List<Report> reports) {
        List<TripRunDto.Stand> stands = new ArrayList<>();
        Map<Long, List<Report>> byBus = new LinkedHashMap<>();
        reports.stream().filter(r -> r.nextSeq() != null)
                .forEach(r -> byBus.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>()).add(r));
        for (List<Report> own : byBus.values()) {
            int i = 0;
            while (i < own.size()) {
                Report start = own.get(i);
                int j = i;
                while (j + 1 < own.size() && TripHandovers.metres(own.get(j + 1), start) <= STAND_METERS) {
                    j++;
                }
                Report end = own.get(j);
                long minutes = Duration.between(start.time(), end.time()).toMinutes();
                int index = trip.index(end.nextSeq());
                boolean atEnd = index == trip.size() - 1 && trip.lastStop() != null
                        && Geo.meters(new double[] {end.lat(), end.lon()}, trip.lastStop())
                        <= AT_END_METERS;
                if (minutes >= STAND_MINUTES && trip.index(start.nextSeq()) > 0 && !atEnd) {
                    Integer growth = start.usableDelay() == null || end.usableDelay() == null ? null
                            : end.usableDelay() - start.usableDelay();
                    stands.add(new TripRunDto.Stand(start.vehicle(), start.time(), end.time(), (int) minutes,
                            trip.stops()[index], index + 1, growth, start.lat(), start.lon()));
                }
                i = j + 1;
            }
        }
        return stands;
    }
}
