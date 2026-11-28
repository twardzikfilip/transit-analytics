package com.transitanalytics.trips;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.timetable.TimetableMath;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.transitanalytics.trips.TripAnomalies.CHANGE_OVERLAP;
import static com.transitanalytics.trips.TripAnomalies.CHANGE_REPORTS;
import static com.transitanalytics.trips.TripAnomalies.IN_SERVICE_DELAY_SECONDS;
import static com.transitanalytics.trips.TripAnomalies.START_STOPS;
import static com.transitanalytics.trips.TripAnomalies.after;
import static com.transitanalytics.trips.TripAnomalies.before;

/**
 * Part of {@link TripAnomalies}: a trip's bus changing on the way, and what became of the bus it left.
 */
public final class TripHandovers {

    private TripHandovers() {
    }

    // ---- Changes of bus -------------------------------------------------------------------------------------

    /**
     * A change of bus during a trip: the first bus's last report on it; the second bus's first report, and the stop
     * it was heading to then (index, 0-based); since when the first bus stood there, and the delays either side.
     */
    record Change(Report fromFirst, Report fromLast, Report toFirst, int toIndex, Report stopped,
                  Integer delayBefore, Integer delayAfter) {

        /** Stop index (0-based) the first bus was heading to when it stopped. */
        int stoppedIndex(ScheduledTrip trip) {
            return trip.index(stopped.nextSeq());
        }

        /**
         * Whether the second bus took over where the first stopped: close by, or heading to the same or next stop. A
         * bus joining further back, away from the spot, didn't.
         */
        boolean samePlace(ScheduledTrip trip) {
            int stoppedAt = stoppedIndex(trip);
            return metres(stopped, toFirst) <= SAME_PLACE_METERS || (toIndex >= stoppedAt && toIndex <= stoppedAt + 1);
        }

        /** Stops between where the first bus stopped and where the second joined, neither seen heading to. */
        int skippedStops(ScheduledTrip trip) {
            return samePlace(trip) ? 0 : Math.max(0, toIndex - stoppedIndex(trip) - 1);
        }

        /** Stops back from where the first bus stopped that the second joined; 0 unless it joined behind. */
        int stopsBack(ScheduledTrip trip) {
            return samePlace(trip) ? 0 : Math.max(0, stoppedIndex(trip) - toIndex);
        }

        /** Minutes the trip stood still: from the first bus stopping for good to the second bus's first report. */
        int standMinutes() {
            return (int) Math.round(Duration.between(stopped.time(), toFirst.time()).toSeconds() / 60.0);
        }
    }

    /** What became of the replaced bus that day. */
    public enum Fate {
        /** Not used again: it didn't move away under a brigade (standing, or no more reports). */
        NOT_USED("not-used"),
        /** Used later on another route or brigade. */
        REASSIGNED("reassigned"),
        /** Back on the same brigade later. */
        RETURNED("returned");

        public final String key;

        Fate(String key) {
            this.key = key;
        }
    }

    /** What a replaced bus that wasn't used again did after standing where it stopped. */
    enum Then {
        /** Stopped reporting while recording went on: switched off, or towed with its tracker off. */
        VANISHED("vanished"),
        /** Moved away without running a brigade (driven or towed to the depot, say). */
        DROVE_OFF("drove-off"),
        /** Still standing there at its latest report. */
        STILL_THERE("still-there");

        public final String key;

        Then(String key) {
            this.key = key;
        }
    }

    /**
     * How a replaced bus that wasn't used again spent the rest of the day: standing where it stopped from {@code
     * stopped} until {@code until}, then {@code then} (moving off at {@code moved}); last seen {@code lastSeen},
     * {@code metres} from that spot.
     */
    record Stand(Instant stopped, Instant until, Then then, Report lastSeen, int metres, Instant moved) {

        int minutes() {
            return (int) Duration.between(stopped, until).toMinutes();
        }
    }

    /**
     * A change of bus explained: the replaced bus's fate (and, when it wasn't used again, how it stood), where the
     * replacement came from (its last route and brigade before; null when it hadn't run another brigade that day),
     * the note, and what each bus did when.
     */
    record Handover(Change change, Fate fate, Stand stand, String fromRoute, String fromBrigade,
                    List<TripAnomalyDto.Phrase> note, List<TripAnomalyDto.Span> spans) {
    }

    /** Reports within this distance of each other are the same place: a bus standing, or a takeover on the spot. */
    static final double STAND_METERS = 50;
    /** A replacement first seen this close to where the first bus stopped took over on the spot. */
    static final double SAME_PLACE_METERS = 300;
    /** A bus must get this far along a trip to have run part of it. */
    static final double RUN_METERS = 200;

    /** Metres between two reports (a flat projection, fine across a city). */
    static double metres(Report a, Report b) {
        return Geo.meters(new double[] {a.lat(), a.lon()}, new double[] {b.lat(), b.lon()});
    }

    static boolean hasBrigade(Report r) {
        return r.brigade() != null && !r.brigade().isBlank();
    }

    /** Whether a report looks like a bus in service: on a brigade, with a plausible delay. */
    static boolean inService(Report r) {
        return hasBrigade(r) && r.delay() != null && Math.abs(r.delay()) <= IN_SERVICE_DELAY_SECONDS;
    }

    /**
     * The first change of bus on a trip: two buses each with {@link #CHANGE_REPORTS} reports on it, one after the
     * other (overlapping at most {@link #CHANGE_OVERLAP}), the first past the trip's first stops (a bus laying
     * over at the start isn't running it) and the second further along than the first got; null if none.
     */
    static Change vehicleChange(ScheduledTrip trip, List<Report> sorted) {
        Map<Long, List<Report>> byVehicle = new LinkedHashMap<>();
        sorted.forEach(r -> byVehicle.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>()).add(r));
        List<List<Report>> buses = byVehicle.values().stream().filter(l -> l.size() >= CHANGE_REPORTS).toList();
        for (int i = 0; i + 1 < buses.size(); i++) {
            // How far the first bus got: a bus giving up often reports the next trip's headsign (and stops) for a
            // while, so only its reports under its usual headsign count.
            List<Report> all = buses.get(i);
            String usual = TimetableMath.mostFrequent(all.stream().map(Report::headsign).toList());
            List<Report> a = all.stream().filter(r -> Objects.equals(r.headsign(), usual)).toList();
            List<Report> b = buses.get(i + 1);
            int aMax = a.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
            int bMax = b.stream().mapToInt(r -> trip.index(r.nextSeq())).max().orElse(0);
            // Where it left the trip: its last report under its usual headsign (a replaced bus may report on).
            Report aLast = a.get(a.size() - 1);
            Report bFirst = b.get(0);
            // A first bus that never moved on the trip didn't run it (a replaced bus reporting on, say).
            boolean moved = a.stream().anyMatch(r -> metres(a.get(0), r) >= RUN_METERS);
            if (aMax >= START_STOPS && bMax > aMax && moved
                    && !aLast.time().isAfter(bFirst.time().plus(CHANGE_OVERLAP))) {
                // Since when the first bus stood where it left the trip, and the delays either side.
                int s = a.size() - 1;
                while (s > 0 && metres(a.get(s - 1), aLast) <= STAND_METERS) {
                    s--;
                }
                Report stopped = a.get(s);
                Integer before = null;
                for (int k = s; k >= 0 && before == null; k--) {
                    before = a.get(k).usableDelay();
                }
                Integer after = b.stream().map(Report::usableDelay).filter(Objects::nonNull).findFirst().orElse(null);
                return new Change(a.get(0), aLast, bFirst, trip.index(bFirst.nextSeq()), stopped, before, after);
            }
        }
        return null;
    }
}
