package com.transitanalytics.service;

import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import com.transitanalytics.service.TripAnomalies.Report;
import com.transitanalytics.service.TripAnomalies.ScheduledTrip;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.transitanalytics.service.TimetableMath.LINE_ORDER;

/**
 * A brigade's day: each trip of its timetable, and what actually ran, from the recorded positions. Outcomes are the
 * ones Abnormal trips shows; what ran (which bus, from which stop to which, how late) comes from the reports of the
 * buses that ran the brigade that day, matched to its trips the same way.
 */
@Service
public class BrigadeDayService {

    /** Leaving between 1 min early and 3 min late is on time. */
    static final int ON_TIME_EARLY = -60;
    static final int ON_TIME_LATE = 180;

    private final JdbcTemplate jdbc;
    private final TripAnomalyService anomalies;
    private final Clock clock;

    public BrigadeDayService(JdbcTemplate jdbc, TripAnomalyService anomalies, Clock clock) {
        this.jdbc = jdbc;
        this.anomalies = anomalies;
        this.clock = clock;
    }

    /** Today's service day (03:00 to 03:00 Warsaw time). */
    public LocalDate today() {
        return ServiceDay.serviceDay(clock.instant());
    }

    /**
     * The brigades of an agency running on a service day. Agencies whose timetable doesn't name brigades (another
     * operator's feed without them) aren't offered.
     *
     * @param agency the agency's timetable id; null or unknown: the city's (the main feed's) agency
     */
    public BrigadeDto.DayList brigades(LocalDate day, String agency) {
        String named = "(t.block_id IS NOT NULL OR strpos(t.gtfs_id, ':') = 0)";
        List<BrigadeDto.Agency> agencies = jdbc.query("""
                SELECT a.gtfs_id, a.name, count(*) AS trips, strpos(a.gtfs_id, ':') > 0 AS other
                FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                WHERE %s AND %s
                GROUP BY a.gtfs_id, a.name
                ORDER BY other, trips DESC, a.name""".formatted(ServiceDay.TRIPS_OF_DAY_SQL, named),
                (rs, i) -> new BrigadeDto.Agency(rs.getString("gtfs_id"), rs.getString("name")),
                ServiceDay.tripsOfDayArgs(day));
        String chosen = agencies.stream().map(BrigadeDto.Agency::id).filter(id -> id.equals(agency)).findFirst()
                .orElse(agencies.isEmpty() ? null : agencies.get(0).id());
        if (chosen == null) {
            return new BrigadeDto.DayList(day, null, agencies, List.of());
        }
        Instant zero = ServiceDay.gtfsTimeZero(day);
        // Trips on the next date's timetable (night lines after midnight) count from that date's time zero.
        long nextDate = Duration.between(zero, ServiceDay.gtfsTimeZero(day.plusDays(1))).toSeconds();
        List<BrigadeDto.BrigadeRef> brigades = new ArrayList<>(jdbc.query("""
                WITH times AS (
                    SELECT %s AS brigade, coalesce(nullif(r.short_name, ''), r.long_name) AS line,
                           (SELECT extract(epoch FROM min(st.departure_time)::interval)::int
                            FROM stop_time st WHERE st.trip_id = t.id) AS dep,
                           (SELECT extract(epoch FROM max(st.arrival_time)::interval)::int
                            FROM stop_time st WHERE st.trip_id = t.id) AS arr
                    FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                    WHERE %s AND %s AND a.gtfs_id = ?),
                trips AS (
                    SELECT brigade, line, dep + shift AS dep, arr + shift AS arr
                    FROM times, LATERAL (SELECT CASE WHEN dep < %d THEN %d ELSE 0 END AS shift) s)
                SELECT brigade, array_agg(DISTINCT line) AS lines, count(*) AS trips, min(dep) AS first, max(arr) AS last
                FROM trips GROUP BY brigade""".formatted(ServiceDay.BRIGADE_SQL, ServiceDay.TRIPS_OF_DAY_SQL, named,
                        ServiceDay.DAY_START_SECONDS, nextDate),
                (rs, i) -> new BrigadeDto.BrigadeRef(rs.getString("brigade"),
                        List.of((String[]) rs.getArray("lines").getArray()).stream().sorted(LINE_ORDER).toList(),
                        rs.getInt("trips"), zero.plusSeconds(rs.getInt("first")), zero.plusSeconds(rs.getInt("last"))),
                day, day.plusDays(1), chosen));
        brigades.sort(Comparator.comparing((BrigadeDto.BrigadeRef b) -> b.lines().get(0), LINE_ORDER)
                .thenComparing(BrigadeDto.BrigadeRef::brigade));
        return new BrigadeDto.DayList(day, chosen, agencies, brigades);
    }

    /** A brigade's trips on a service day, each with its outcome and what ran. */
    public BrigadeDto.Day day(String brigade, LocalDate day) {
        List<ScheduledTrip> trips = new ArrayList<>(anomalies.trips(day, brigade));
        trips.sort(Comparator.comparing(ScheduledTrip::departure));
        if (trips.isEmpty()) {
            return new BrigadeDto.Day(brigade, day, summary(List.of()), List.of());
        }
        Instant now = clock.instant();
        Instant end = TripAnomalyService.reportsEnd(day, trips);
        Instant from = ServiceDay.start(day);
        List<Report> reports = from.isBefore(now)
                ? anomalies.reports(from, end.isAfter(now) ? now : end, brigade) : List.of();
        // The outcomes Abnormal trips shows, when it has them current; else the brigade's trips judged on their own
        // (judging the whole day takes a while), from the same reports.
        Map<String, Outcome> outcomes = new HashMap<>();
        for (Outcome o : anomalies.currentOutcomes(day).orElseGet(() -> anomalies.judge(trips, reports, day, now))) {
            outcomes.put(key(o.line(), o.departure(), o.arrival()), o);
        }
        Map<ScheduledTrip, List<Report>> matched = TripMatching.matchReports(TripMatching.byBrigade(trips),
                TripMatching.withBrigades(reports));

        String outbound = trips.get(0).stops()[trips.get(0).size() - 1];
        List<BrigadeDto.Trip> result = new ArrayList<>();
        for (ScheduledTrip trip : trips) {
            Outcome outcome = outcomes.get(key(trip.line(), trip.departure(), trip.arrival()));
            Kind kind = outcome == null ? Kind.NOT_RECORDED : outcome.kind();
            String note = outcome == null || outcome.trip() == null ? null : outcome.trip().note();
            List<BrigadeDto.ActualRun> runs = runs(trip, matched.getOrDefault(trip, List.of()));
            int direction = Objects.equals(trip.stops()[trip.size() - 1], outbound) ? 0 : 1;
            result.add(new BrigadeDto.Trip(trip.tripId(), lineName(trip.line()), trip.headsign(), trip.stops()[0],
                    trip.stops()[trip.size() - 1], direction, trip.departure(), trip.arrival(), trip.size(),
                    kind.key, note, runs, missing(trip, kind, runs)));
        }
        return new BrigadeDto.Day(brigade, day, summary(result), result);
    }

    private static String key(String line, Instant departure, Instant arrival) {
        return line + "|" + departure + "|" + arrival;
    }

    /** A line's name as people know it (another operator's ids are prefixed, e.g. "pksnova:500"). */
    private static String lineName(String line) {
        return line.substring(line.indexOf(':') + 1);
    }

    /** What ran: the trip's reports split where the bus changes, each part from its first stop to its last. */
    static List<BrigadeDto.ActualRun> runs(ScheduledTrip trip, List<Report> reports) {
        List<Report> sorted = reports.stream().filter(r -> r.nextSeq() != null)
                .sorted(Comparator.comparing(Report::time)).toList();
        List<BrigadeDto.ActualRun> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= sorted.size(); i++) {
            if (i == sorted.size() || sorted.get(i).vehicleId() != sorted.get(start).vehicleId()) {
                runs.add(run(trip, sorted.subList(start, i)));
                start = i;
            }
        }
        return runs;
    }

    private static BrigadeDto.ActualRun run(ScheduledTrip trip, List<Report> part) {
        int from = part.stream().mapToInt(r -> trip.index(r.nextSeq())).min().orElse(0);
        int to = part.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
        // The delay setting off: once it heads beyond its first stop (before, it may be waiting at the terminus).
        Integer startDelay = part.stream().filter(r -> trip.index(r.nextSeq()) > from).map(Report::usableDelay)
                .filter(Objects::nonNull).findFirst()
                .orElse(part.stream().map(Report::usableDelay).filter(Objects::nonNull).findFirst().orElse(null));
        Integer endDelay = part.stream().map(Report::usableDelay).filter(Objects::nonNull)
                .reduce((a, b) -> b).orElse(null);
        Instant departure = startDelay == null ? part.get(0).time()
                : trip.scheduledAt(trip.seqs()[from]).plusSeconds(startDelay);
        Instant arrival = endDelay == null ? part.get(part.size() - 1).time()
                : trip.scheduledAt(trip.seqs()[to]).plusSeconds(endDelay);
        if (arrival.isBefore(departure)) {
            arrival = departure;
        }
        return new BrigadeDto.ActualRun(part.get(0).vehicle(), from + 1, to + 1, trip.stops()[from], trip.stops()[to],
                departure, arrival, startDelay, endDelay);
    }

    /**
     * The parts of a judged trip nobody ran (or no bus was seen on): before the first run, between runs where stops
     * were skipped, and after the last. A completed trip may end a stop or two short of its last report: not missing.
     */
    static List<BrigadeDto.Missing> missing(ScheduledTrip trip, Kind kind, List<BrigadeDto.ActualRun> runs) {
        int last = trip.size();
        if (kind == Kind.MISSED || kind == Kind.CANCELLED) {
            return List.of(new BrigadeDto.Missing(1, last, trip.departure(), trip.arrival(), "not-run"));
        }
        if (runs.isEmpty() || !Set.of(Kind.LOST, Kind.SHORT_TURNED, Kind.NO_SIGNAL, Kind.VEHICLE_CHANGED)
                .contains(kind)) {
            return List.of();
        }
        String why = kind == Kind.NO_SIGNAL ? "not-seen" : "not-run";
        List<BrigadeDto.Missing> missing = new ArrayList<>();
        int covered = 1;
        for (BrigadeDto.ActualRun run : runs) {
            if (run.fromStop() > covered) {
                missing.add(gap(trip, covered, run.fromStop(), why));
            }
            covered = Math.max(covered, run.toStop());
        }
        if (kind != Kind.VEHICLE_CHANGED && covered < last) {
            missing.add(gap(trip, covered, last, why));
        }
        return missing;
    }

    private static BrigadeDto.Missing gap(ScheduledTrip trip, int fromStop, int toStop, String why) {
        return new BrigadeDto.Missing(fromStop, toStop, trip.scheduledAt(trip.seqs()[fromStop - 1]),
                trip.scheduledAt(trip.seqs()[toStop - 1]), why);
    }

    static BrigadeDto.Summary summary(List<BrigadeDto.Trip> trips) {
        int judged = 0;
        int completed = 0;
        int abnormal = 0;
        List<Integer> startDelays = new ArrayList<>();
        Integer maxDelay = null;
        Set<String> buses = new LinkedHashSet<>();
        for (BrigadeDto.Trip t : trips) {
            boolean isJudged = !t.kind().equals(Kind.NOT_RECORDED.key) && !t.kind().equals(Kind.PENDING.key);
            if (isJudged) {
                judged++;
                if (t.kind().equals(Kind.COMPLETED.key)) {
                    completed++;
                } else {
                    abnormal++;
                }
            }
            for (BrigadeDto.ActualRun r : t.runs()) {
                buses.add(r.bus());
                for (Integer d : new Integer[] {r.startDelay(), r.endDelay()}) {
                    if (d != null && (maxDelay == null || d > maxDelay)) {
                        maxDelay = d;
                    }
                }
            }
            if (isJudged && !t.runs().isEmpty() && t.runs().get(0).startDelay() != null) {
                startDelays.add(t.runs().get(0).startDelay());
            }
        }
        int onTime = (int) startDelays.stream().filter(d -> d >= ON_TIME_EARLY && d <= ON_TIME_LATE).count();
        List<Integer> sorted = startDelays.stream().sorted().toList();
        Integer median = sorted.isEmpty() ? null : sorted.get(sorted.size() / 2);
        return new BrigadeDto.Summary(trips.size(), judged, completed, abnormal, onTime, startDelays.size(), median,
                maxDelay, List.copyOf(buses));
    }
}
