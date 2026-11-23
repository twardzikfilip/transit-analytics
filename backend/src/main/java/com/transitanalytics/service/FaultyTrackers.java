package com.transitanalytics.service;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.transitanalytics.service.TimetableMath.LINE_ORDER;

/**
 * Buses whose tracker probably fails: on several days, trips they ran had parts with no signal (the bus stopped
 * reporting and was back later). One such trip is chance; the same bus again and again, on different lines and days,
 * is its tracker. Their gaps are tracking, not service, and the tracker is worth checking.
 */
@Service
public class FaultyTrackers {

    /** Looked at over this many days up to now. */
    static final int DAYS = 7;
    /** A bus with no-signal trips on at least this many days… */
    static final int MIN_DAYS = 3;
    /** …and at least this many of them… */
    static final int MIN_TRIPS = 5;
    /** …at least this share of the trips it ran (fewer is chance: any bus loses signal now and then). */
    static final double MIN_SHARE = 0.10;
    /** The list is worked out again after this long (it takes the outcomes of a week). */
    static final Duration CACHE_FOR = Duration.ofMinutes(10);
    /** Kinds of trip a bus ran (in full or in part): the ones its no-signal trips are a share of. */
    private static final Set<Kind> RUN = Set.of(Kind.COMPLETED, Kind.DETOUR, Kind.VEHICLE_CHANGED, Kind.NO_SIGNAL,
            Kind.SHORT_TURNED, Kind.LOST);

    private final JdbcTemplate jdbc;
    private final TripAnomalyService anomalies;
    private final Clock clock;
    private final RecentCache<Boolean, TripAnomalyDto.FaultyTrackers> cache;

    public FaultyTrackers(JdbcTemplate jdbc, TripAnomalyService anomalies, Clock clock) {
        this.jdbc = jdbc;
        this.anomalies = anomalies;
        this.clock = clock;
        this.cache = new RecentCache<>(clock, CACHE_FOR, 1);
    }

    /**
     * The buses with no-signal trips on {@link #MIN_DAYS} days or more of the last {@link #DAYS}, at least
     * {@link #MIN_TRIPS} and {@link #MIN_SHARE} of the trips they ran; most first.
     */
    public TripAnomalyDto.FaultyTrackers faultyTrackers() {
        return cache.get(true, this::compute);
    }

    private TripAnomalyDto.FaultyTrackers compute() {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(DAYS));
        return new TripAnomalyDto.FaultyTrackers(from, now, MIN_DAYS, MIN_TRIPS, (int) Math.round(MIN_SHARE * 100),
                faulty(anomalies.outcomes(from, now)));
    }

    private static final class Tally {
        int trips;
        int noSignal;
        final Set<LocalDate> days = new TreeSet<>();
        final Set<String> lines = new TreeSet<>(LINE_ORDER);
        Instant last;
    }

    /** The buses the outcomes show with failing trackers. */
    List<TripAnomalyDto.FaultyTracker> faulty(List<Outcome> outcomes) {
        Map<Long, Tally> byBus = new HashMap<>();
        for (Outcome o : outcomes) {
            if (!RUN.contains(o.kind())) {
                continue;
            }
            for (Long bus : o.vehicleIds()) {
                Tally tally = byBus.computeIfAbsent(bus, k -> new Tally());
                tally.trips++;
                if (o.kind() == Kind.NO_SIGNAL) {
                    tally.noSignal++;
                    tally.days.add(ServiceDay.serviceDay(o.departure()));
                    tally.lines.add(o.line());
                    if (tally.last == null || o.departure().isAfter(tally.last)) {
                        tally.last = o.departure();
                    }
                }
            }
        }
        byBus.values().removeIf(t -> t.days.size() < MIN_DAYS || t.noSignal < MIN_TRIPS
                || t.noSignal < MIN_SHARE * t.trips);
        if (byBus.isEmpty()) {
            return List.of();
        }
        Map<Long, String> numbers = new HashMap<>();
        jdbc.query("SELECT id, vehicle_number FROM vehicle WHERE id = ANY (?)",
                rs -> {
                    numbers.put(rs.getLong("id"), rs.getString("vehicle_number"));
                }, (Object) byBus.keySet().toArray(Long[]::new));
        List<TripAnomalyDto.FaultyTracker> faulty = new ArrayList<>();
        byBus.forEach((id, t) -> faulty.add(new TripAnomalyDto.FaultyTracker(id, numbers.get(id), t.noSignal,
                t.trips, t.days.size(), t.last, List.copyOf(t.lines))));
        faulty.sort(Comparator.comparingInt(TripAnomalyDto.FaultyTracker::noSignalTrips).reversed()
                .thenComparing(TripAnomalyDto.FaultyTracker::bus, Comparator.nullsLast(Comparator.naturalOrder())));
        return faulty;
    }
}
