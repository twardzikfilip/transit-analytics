package com.transitanalytics.trips;

import com.transitanalytics.geo.Geo;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.transitanalytics.trips.TripAnomalies.MATCH_TOLERANCE;

/**
 * Part of {@link TripAnomalies}: which scheduled trip each report belongs to (by brigade, time, route and headsign),
 * filling in brigades the feed leaves out and leaving out layovers at the last stop.
 */
public final class TripMatching {

    private TripMatching() {
    }

    /** The trips by brigade, each brigade's in departure order. */
    static Map<String, List<ScheduledTrip>> byBrigade(List<ScheduledTrip> trips) {
        Map<String, List<ScheduledTrip>> byBrigade = trips.stream().filter(t -> t.brigade() != null)
                .collect(Collectors.groupingBy(ScheduledTrip::brigade));
        byBrigade.values().forEach(list -> list.sort(Comparator.comparing(ScheduledTrip::departure)));
        return byBrigade;
    }

    /**
     * Each trip's reports: every report goes to the trip of its brigade it matches, or stays on its bus's previous
     * trip (see {@link #staysOn}).
     *
     * @param reports ordered by vehicle and time, blank brigades filled in ({@link #withBrigades})
     */
    static Map<ScheduledTrip, List<Report>> matchReports(Map<String, List<ScheduledTrip>> byBrigade,
                                                         List<Report> reports) {
        Map<ScheduledTrip, List<Report>> matched = new IdentityHashMap<>();
        Report previous = null;
        ScheduledTrip previousTrip = null;
        for (Report r : reports) {
            ScheduledTrip trip = staysOn(previous, previousTrip, r) ? previousTrip
                    : match(r, byBrigade.get(r.brigade()));
            if (trip != null) {
                matched.computeIfAbsent(trip, k -> new ArrayList<>()).add(r);
            }
            previous = r;
            previousTrip = trip;
        }
        matched.replaceAll(TripMatching::withoutLayover);
        return matched;
    }

    /** A bus heading to a trip's last stop this close to it has arrived. */
    static final double AT_TERMINUS_METERS = 150;

    /**
     * A trip's reports up to each bus's arrival at its last stop: the first report heading there from within
     * {@link #AT_TERMINUS_METERS}. The feed often keeps a bus waiting at the terminus on the trip it finished, its delay
     * growing by the second until its next trip; those reports are the layover, not the trip running late.
     *
     * @param reports ordered by vehicle and time
     */
    public static List<Report> withoutLayover(ScheduledTrip trip, List<Report> reports) {
        if (trip.lastStop() == null) {
            return reports;
        }
        int last = trip.seqs()[trip.size() - 1];
        Set<Long> arrived = new HashSet<>();
        List<Report> kept = new ArrayList<>(reports.size());
        for (Report r : reports) {
            if (arrived.contains(r.vehicleId())) {
                continue;
            }
            kept.add(r);
            if (r.nextSeq() != null && r.nextSeq() >= last
                    && Geo.meters(new double[] {r.lat(), r.lon()}, trip.lastStop()) <= AT_TERMINUS_METERS) {
                arrived.add(r.vehicleId());
            }
        }
        return kept.size() == reports.size() ? reports : kept;
    }

    /** A report with an outlying delay stays on its bus's trip when this close after the bus's previous report. */
    static final Duration STAYS_ON_FOR = Duration.ofMinutes(5);

    /**
     * Whether a report stays on the trip its bus's previous report was matched to: its delay is an outlier, so the
     * time it was running to is unknown, and it is the same bus, route, brigade and headsign moments later. A bus
     * standing mid-trip keeps reporting the trip with its delay growing by the minute; once past half an hour the
     * delay no longer places it, and by the clock alone it would look like the brigade's next trip.
     */
    static boolean staysOn(Report previous, ScheduledTrip previousTrip, Report r) {
        return previousTrip != null && r.delay() != null && r.usableDelay() == null
                && previous.vehicleId() == r.vehicleId() && Objects.equals(previous.brigade(), r.brigade())
                && Objects.equals(previous.route(), r.route()) && Objects.equals(previous.headsign(), r.headsign())
                && !previous.time().plus(STAYS_ON_FOR).isBefore(r.time());
    }

    /** A report without a brigade takes it from the bus's report next to it, on the same route, this close in time. */
    static final Duration BRIGADE_FROM_NEIGHBOUR = Duration.ofMinutes(10);

    /**
     * The reports with blank brigades filled in from the same bus's previous (else next) report on the same route,
     * when close in time: the feed sometimes drops the brigade for a moment, typically at a terminus.
     */
    public static List<Report> withBrigades(List<Report> reports) {
        List<Report> filled = new ArrayList<>(reports);
        for (int i = 0; i < filled.size(); i++) {
            Report r = filled.get(i);
            if (r.brigade() != null && !r.brigade().isBlank()) {
                continue;
            }
            String brigade = neighbourBrigade(filled, i, -1);
            if (brigade == null) {
                brigade = neighbourBrigade(filled, i, 1);
            }
            filled.set(i, new Report(r.vehicleId(), r.vehicle(), r.time(), r.route(), brigade, r.headsign(),
                    r.delay(), r.nextSeq(), r.lat(), r.lon()));
        }
        return filled;
    }

    private static String neighbourBrigade(List<Report> reports, int i, int step) {
        Report r = reports.get(i);
        for (int j = i + step; j >= 0 && j < reports.size(); j += step) {
            Report n = reports.get(j);
            if (n.vehicleId() != r.vehicleId() || !n.route().equals(r.route())
                    || Duration.between(n.time(), r.time()).abs().compareTo(BRIGADE_FROM_NEIGHBOUR) > 0) {
                return null;
            }
            if (n.brigade() != null && !n.brigade().isBlank()) {
                return n.brigade();
            }
        }
        return null;
    }

    /** A different headsign counts as being this much further off in time. */
    private static final long HEADSIGN_PENALTY_SECONDS = 120;

    /**
     * The trip of the brigade a report belongs to: on the same route, the one scheduled to leave the report's next stop
     * closest to the report's scheduled time (within {@link #MATCH_TOLERANCE}); a different headsign counts as two
     * minutes further. The feed's headsigns don't always read as the timetable's, so the stop and time decide: a bus
     * waiting at a terminus belongs to the trip it will start, not the one it finished.
     */
    public static ScheduledTrip match(Report r, List<ScheduledTrip> brigadeTrips) {
        if (brigadeTrips == null || r.nextSeq() == null) {
            return null;
        }
        Instant scheduled = r.scheduled();
        ScheduledTrip best = null;
        long bestScore = Long.MAX_VALUE;
        for (ScheduledTrip t : brigadeTrips) {
            if (!t.route().equals(r.route())) {
                continue;
            }
            long score = Math.abs(Duration.between(t.scheduledAt(r.nextSeq()), scheduled).toSeconds())
                    + (Objects.equals(t.headsign(), r.headsign()) ? 0 : HEADSIGN_PENALTY_SECONDS);
            if (score < bestScore) {
                best = t;
                bestScore = score;
            }
        }
        return bestScore <= MATCH_TOLERANCE.toSeconds() ? best : null;
    }
}
