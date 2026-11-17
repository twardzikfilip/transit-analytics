package com.transitanalytics.service;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import com.transitanalytics.service.TripAnomalies.Recording;
import com.transitanalytics.service.TripAnomalies.Report;
import com.transitanalytics.service.TripAnomalies.ScheduledTrip;
import com.transitanalytics.service.TripAnomalies.Shape;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TripAnomaliesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final double LON = 23.15;
    /** Stops A–E northwards, 330 m apart; trips towards "North" run A→E, towards "South" E→A, 2 min a stop. */
    private static final String[] NAMES = {"A", "B", "C", "D", "E"};
    private static final Map<String, Shape> SHAPES = Map.of("S", TripAnomalies.shape(List.of(
            new double[] {53.10, LON}, new double[] {53.112, LON})));
    /** Recording all morning, but not 09:30–09:40. */
    private static final Recording RECORDING = recording();
    private static final Instant NOON = at("12:00");

    private static Instant at(String hhmm) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }

    private static Recording recording() {
        Set<Long> minutes = new HashSet<>();
        for (long m = at("05:00").getEpochSecond() / 60; m < at("13:00").getEpochSecond() / 60; m++) {
            minutes.add(m);
        }
        for (long m = at("09:30").getEpochSecond() / 60; m < at("09:40").getEpochSecond() / 60; m++) {
            minutes.remove(m);
        }
        return new Recording(minutes);
    }

    private static ScheduledTrip trip(String brigade, String headsign, String departure) {
        boolean north = headsign.equals("North");
        String[] stops = new String[5];
        for (int i = 0; i < 5; i++) {
            stops[i] = NAMES[north ? i : 4 - i];
        }
        return new ScheduledTrip(brigade + "_" + departure, "9", brigade, headsign, at(departure),
                at(departure).plusSeconds(8 * 60), new int[] {0, 1, 2, 3, 4}, stops, new int[] {0, 120, 240, 360, 480},
                "S");
    }

    /** Where a bus heading to stop {@code seq} of a trip is: at the stop before it (or the first). */
    private static double lat(ScheduledTrip trip, int seq) {
        int at = Math.max(0, seq - 1);
        int stop = trip.headsign().equals("North") ? at : 4 - at;
        return 53.10 + stop * 0.003;
    }

    /** A report of a bus on a trip, heading to stop {@code seq}, at the time it's due to leave the stop before. */
    private static Report report(long vehicle, ScheduledTrip trip, int seq, long secondsLater, double lonOffset) {
        Instant t = trip.scheduledAt(Math.max(0, seq - 1)).plusSeconds(secondsLater);
        return new Report(vehicle, "V" + vehicle, t, trip.line(), trip.brigade(), trip.headsign(), 0, seq,
                lat(trip, seq), LON + lonOffset);
    }

    /** Reports every 20 s along stops {@code seqs} (three each). */
    private static List<Report> drive(long vehicle, ScheduledTrip trip, int... seqs) {
        List<Report> reports = new ArrayList<>();
        for (int seq : seqs) {
            for (int k = 0; k < 3; k++) {
                reports.add(report(vehicle, trip, seq, k * 20L, 0));
            }
        }
        return reports;
    }

    private static Map<String, Outcome> judge(List<ScheduledTrip> trips, List<Report> reports, Instant now) {
        List<Report> sorted = reports.stream()
                .sorted(Comparator.comparingLong(Report::vehicleId).thenComparing(Report::time)).toList();
        List<Outcome> outcomes = TripAnomalies.judge(trips, sorted, RECORDING, SHAPES, now);
        Map<String, Outcome> byTrip = new java.util.LinkedHashMap<>();
        for (int i = 0; i < trips.size(); i++) {
            byTrip.put(trips.get(i).tripId(), outcomes.get(i));
        }
        return byTrip;
    }

    @Test
    void judgesEachTripAgainstWhatTheBusesDid() {
        // Brigade 1: runs 07:00 in full, 07:15 with a detour, misses 07:30, runs 07:45 but turns before the end.
        ScheduledTrip full = trip("009-01", "North", "07:00");
        ScheduledTrip detour = trip("009-01", "South", "07:15");
        ScheduledTrip missed = trip("009-01", "North", "07:30");
        ScheduledTrip shortTurn = trip("009-01", "South", "07:45");
        // Brigade 2: no bus all day. Brigade 3: its bus disappears midway. Brigade 4: its bus loses signal.
        ScheduledTrip cancelled = trip("009-02", "North", "07:00");
        ScheduledTrip lost = trip("009-03", "North", "08:00");
        ScheduledTrip noSignal = trip("009-04", "North", "08:00");
        ScheduledTrip noSignalNext = trip("009-04", "South", "08:15");
        // During the gap in recording, and after noon.
        ScheduledTrip unrecorded = trip("009-05", "North", "09:30");
        ScheduledTrip future = trip("009-05", "South", "12:30");

        List<Report> reports = new ArrayList<>();
        reports.addAll(drive(1, full, 0, 1, 2, 3, 4));
        reports.addAll(drive(1, detour, 0, 1));
        for (int seq = 2; seq <= 3; seq++) {
            for (int k = 0; k < 3; k++) {
                reports.add(report(1, detour, seq, k * 20L, 0.005)); // 330 m east of the route
            }
        }
        reports.addAll(drive(1, detour, 4));
        // 07:30: bus 1 is seen on line 10 meanwhile.
        reports.add(new Report(1, "V1", at("07:35"), "10", "010-01", "Rynek", 0, 3, 53.2, 23.2));
        reports.addAll(drive(1, shortTurn, 0, 1, 2));
        Report lastOn = drive(1, shortTurn, 2).get(2);
        reports.add(new Report(1, "V1", lastOn.time().plusSeconds(20), "9", "009-01", "Zajezdnia", 0, 0, 53.0, LON));
        reports.addAll(drive(3, lost, 0, 1, 2));
        reports.addAll(drive(4, noSignal, 0, 1, 2));
        reports.addAll(drive(4, noSignalNext, 0, 1, 2, 3, 4));

        Map<String, Outcome> outcomes = judge(List.of(full, detour, missed, shortTurn, cancelled, lost, noSignal,
                noSignalNext, unrecorded, future), reports, NOON);

        assertThat(outcomes.values()).extracting(Outcome::kind).containsExactly(Kind.COMPLETED, Kind.DETOUR,
                Kind.MISSED, Kind.SHORT_TURNED, Kind.CANCELLED, Kind.LOST, Kind.NO_SIGNAL, Kind.COMPLETED,
                Kind.NOT_RECORDED, Kind.PENDING);

        TripAnomalyDto.Trip d = outcomes.get(detour.tripId()).trip();
        assertThat(d.detourMeters()).isBetween(320, 340);
        assertThat(d.detourMinutes()).as("07:17:00 to 07:19:40, and the last report's 20 s").isEqualTo(3);
        assertThat(d.detourFrom()).isEqualTo("D");
        assertThat(d.note()).startsWith("Run in full. Off the route for 3 min, up to");
        assertThat(d.vehicles()).containsExactly("V1");

        assertThat(outcomes.get(missed.tripId()).trip().note())
                .isEqualTo("Between the brigade's runs. Bus V1 was on line 10 (brigade 010-01).");
        assertThat(outcomes.get(missed.tripId()).vehicleIds()).as("the brigade's bus that day").containsExactly(1L);
        assertThat(outcomes.get(detour.tripId()).vehicleIds()).containsExactly(1L);
        assertThat(outcomes.get(full.tripId()).vehicleIds()).as("normal trips aren't listed").isEmpty();

        TripAnomalyDto.Trip s = outcomes.get(shortTurn.tripId()).trip();
        assertThat(s.fromStop()).isEqualTo(1);
        assertThat(s.toStop()).isEqualTo(3);
        assertThat(s.coveredTo()).isEqualTo("C");
        assertThat(s.note()).isEqualTo("Left the trip heading to C (stop 3 of 5) at 07:47 and went on to the "
                + "brigade's next trip towards Zajezdnia.");
        assertThat(s.startDelay()).isZero();

        assertThat(outcomes.get(cancelled.tripId()).trip().note())
                .isEqualTo("No bus ran brigade 009-02 all day while recording.");
        assertThat(outcomes.get(lost.tripId()).trip().note())
                .isEqualTo("Bus V3 stopped reporting heading to C (stop 3 of 5) at 08:02 and wasn't seen again "
                        + "that day.");
        assertThat(outcomes.get(noSignal.tripId()).trip().note())
                .isEqualTo("Bus V4 stopped reporting heading to C (stop 3 of 5) at 08:02; back at 08:15 on the "
                        + "brigade's next trip towards South, so it probably finished the trip.");

        List<Outcome> all = new ArrayList<>(outcomes.values());
        TripAnomalyDto.Summary summary = TripAnomalies.summary(all);
        assertThat(summary).isEqualTo(new TripAnomalyDto.Summary(10, 8, 2, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0));
        assertThat(TripAnomalies.lines(all)).singleElement()
                .isEqualTo(new TripAnomalyDto.LineCounts("9", 8, 2, 1, 0, 1, 1, 1, 1, 1));
    }

    @Test
    void tellsShortTurnsFromLateStartsAndKeepsTripsPendingWhileTheBusRunsLate() {
        ScheduledTrip first = trip("009-01", "North", "07:00");
        ScheduledTrip second = trip("009-01", "South", "07:15");
        List<Report> reports = new ArrayList<>(drive(1, first, 0, 1, 2, 3, 4));
        // Bus 1 joins the second trip at stop 3, reporting all along (on line 10 just before): a short turn.
        reports.add(new Report(1, "V1", at("07:18").plusSeconds(40), "10", "010-01", "Rynek", 0, 5, 53.2, 23.2));
        reports.addAll(drive(1, second, 3, 4));
        // Bus 2 starts reporting midway on brigade 2: a gap in tracking, not service.
        ScheduledTrip other = trip("009-02", "North", "07:00");
        reports.addAll(drive(2, other, 3, 4));

        Map<String, Outcome> outcomes = judge(List.of(first, second, other), reports, NOON);
        assertThat(outcomes.get(second.tripId()).kind()).isEqualTo(Kind.SHORT_TURNED);
        assertThat(outcomes.get(second.tripId()).trip().note()).startsWith(
                "Started midway: joined heading to B (stop 4 of 5) at 07:19, coming from line 10 (brigade 010-01).");
        assertThat(outcomes.get(other.tripId()).kind()).isEqualTo(Kind.NO_SIGNAL);
        assertThat(outcomes.get(other.tripId()).trip().note())
                .isEqualTo("Bus V2 started reporting midway, heading to D (stop 4 of 5) at 07:04.");

        // At 07:27 the next trip hasn't been seen, but its bus is still on the brigade (running late): pending.
        ScheduledTrip third = trip("009-01", "North", "07:15");
        List<Report> late = new ArrayList<>(drive(1, first, 0, 1, 2));
        late.add(new Report(1, "V1", at("07:26"), "9", "009-01", "North", 900, 3, 53.106, LON));
        Map<String, Outcome> now = judge(List.of(first, third), late, at("07:27"));
        assertThat(now.get(third.tripId()).kind()).isEqualTo(Kind.PENDING);
        // Not yet 10 minutes after it was due, a trip not seen is pending too.
        ScheduledTrip notDue = trip("009-09", "North", "07:20");
        assertThat(judge(List.of(first, notDue), late, at("07:27")).get(notDue.tripId()).kind())
                .isEqualTo(Kind.PENDING);
        // But with no bus on the brigade, a trip due 10 minutes ago is missing so far.
        ScheduledTrip due = trip("009-09", "North", "07:10");
        assertThat(judge(List.of(first, due), late, at("07:27")).get(due.tripId())).satisfies(o -> {
                    assertThat(o.kind()).isEqualTo(Kind.CANCELLED);
                    assertThat(o.trip().note()).startsWith("So far: No bus ran brigade 009-09");
                });
    }

    /** Bus 1 runs the trip to D and gives up; bus 2 takes over a minute later, heading to E. */
    private static List<Report> changeOfBus(ScheduledTrip trip) {
        List<Report> reports = new ArrayList<>(drive(1, trip, 0, 1, 2, 3));
        // The feed shows bus 1 on its brigade's next trip (towards South, heading to E) once, where it stands.
        Report gaveUp = report(1, trip, 4, -60, 0);
        reports.add(new Report(1, "V1", gaveUp.time(), "9", "009-01", "South", 0, 4, gaveUp.lat(), LON));
        reports.addAll(drive(2, trip, 4));
        return reports;
    }

    private static Report elsewhere(long vehicle, String time, String line, String brigade) {
        return new Report(vehicle, "V" + vehicle, at(time), line, brigade, "Rynek", 0, 2, 53.2, 23.2);
    }

    @Test
    void findsABusChangedMidwayButNotABusLayingOverOrAMislabelledReport() {
        ScheduledTrip trip = trip("009-01", "North", "07:00");
        List<Report> reports = new ArrayList<>(changeOfBus(trip));
        // Bus 3 reports the brigade twice by mistake.
        reports.add(report(3, trip, 2, 10, 0));
        reports.add(report(3, trip, 2, 30, 0));

        Outcome changed = judge(List.of(trip), reports, NOON).get(trip.tripId());
        assertThat(changed.kind()).isEqualTo(Kind.VEHICLE_CHANGED);
        assertThat(changed.trip().vehicles()).containsExactly("V1", "V3", "V2");
        assertThat(changed.trip().note()).isEqualTo("Run in full. Bus V1 stopped heading to D (stop 4 of 5) at "
                + "07:04; 2 min later bus V2 took over at the same place, heading to E (stop 5). Delay +0:00 before, "
                + "+0:00 after. Bus V2 hadn't run any other brigade that day (from the depot). Bus V1 wasn't seen "
                + "again that day: possibly broken down.");
        // It stood heading to D from 07:04:00 (three reports there); bus 2 first reported at 07:06:00.
        assertThat(changed.trip().changeMinutes()).isEqualTo(2);
        assertThat(changed.trip().changeStop()).isEqualTo("D");
        assertThat(changed.trip().joinStop()).isEqualTo("E");
        assertThat(changed.trip().skippedStops()).isZero();
        assertThat(changed.trip().delayBeforeChange()).isZero();
        assertThat(changed.trip().delayAfterChange()).isZero();
        assertThat(changed.trip().changeFate()).isEqualTo("broken-down");
        assertThat(changed.trip().changeAt()).as("when bus 1 stopped for good").isEqualTo(at("07:04"));
        assertThat(changed.vehicleIds()).containsExactly(1L, 3L, 2L);
        assertThat(changed.trip().replacementLine()).isNull();
        assertThat(TripAnomalies.summary(List.of(changed))).satisfies(s -> {
            assertThat(s.vehicleChanged()).isEqualTo(1);
            assertThat(s.changedBrokenDown()).isEqualTo(1);
            assertThat(s.changedReassigned()).isZero();
        });

        // A bus laying over at the first stop while another runs the trip: no change.
        List<Report> layover = new ArrayList<>(drive(1, trip, 0));
        layover.addAll(drive(2, trip, 0, 1, 2, 3, 4));
        assertThat(judge(List.of(trip), layover, NOON).get(trip.tripId()).kind()).isEqualTo(Kind.COMPLETED);
        assertThat(TripAnomalies.vehicleChange(trip, layover)).isNull();
    }

    @Test
    void tellsWhereTheReplacementCameFromAndWhatBecameOfTheReplacedBus() {
        ScheduledTrip trip = trip("009-01", "North", "07:00");
        Function<List<Report>, TripAnomalyDto.Trip> judged = extra -> {
            List<Report> reports = new ArrayList<>(changeOfBus(trip));
            reports.addAll(extra);
            return judge(List.of(trip), reports, NOON).get(trip.tripId()).trip();
        };

        // Bus 2 came straight from line 10; bus 1 runs line 12 from 08:00 for a quarter of an hour: reassigned.
        TripAnomalyDto.Trip reassigned = judged.apply(List.of(elsewhere(2, "07:05", "10", "010-02"),
                elsewhere(1, "08:00", "12", "012-01"), elsewhere(1, "08:15", "12", "012-01")));
        assertThat(reassigned.changeFate()).isEqualTo("reassigned");
        assertThat(reassigned.replacementLine()).isEqualTo("10");
        assertThat(reassigned.replacementBrigade()).isEqualTo("010-02");
        assertThat(reassigned.note()).endsWith("Bus V2 came straight from line 10 (brigade 010-02), until 07:05. "
                + "Bus V1 was used again on line 12 (brigade 012-01) from 08:00.");

        // Bus 2 ran another brigade of the line hours before; bus 1 is back on its brigade later.
        TripAnomalyDto.Trip returned = judged.apply(List.of(elsewhere(2, "05:30", "9", "009-07"),
                elsewhere(1, "09:00", "9", "009-01"), elsewhere(1, "09:20", "9", "009-01")));
        assertThat(returned.changeFate()).isEqualTo("returned");
        assertThat(returned.note()).endsWith("Bus V2 had run brigade 009-07 of the same line earlier that day, "
                + "until 05:30. Bus V1 was back on the brigade at 09:00.");

        // Bus 1 stands where it gave up, then is driven off reporting its brigade once: not used again.
        Report gaveUp = changeOfBus(trip).get(12);
        TripAnomalyDto.Trip stood = judged.apply(List.of(
                new Report(1, "V1", at("07:20"), "9", "009-01", "South", 2000, 4, gaveUp.lat(), LON)));
        assertThat(stood.note()).endsWith("Bus V1 stood there until 07:20 and wasn't used again that day: "
                + "possibly broken down.");
        TripAnomalyDto.Trip towed = judged.apply(List.of(elsewhere(1, "07:30", "9", "009-01")));
        assertThat(towed.changeFate()).isEqualTo("broken-down");
        assertThat(towed.note()).endsWith("Bus V1 was last seen at 07:30 and wasn't used again that day: "
                + "possibly broken down.");
        // Driven off still logged in to its brigade for 20 minutes, its delay over half an hour: not in service.
        List<Report> leaving = new ArrayList<>();
        for (int m = 30; m <= 50; m += 5) {
            leaving.add(new Report(1, "V1", at("07:" + m), "9", "009-01", "South", 2000 + m * 60, 2, 53.2, 23.2));
        }
        assertThat(judged.apply(leaving).changeFate()).isEqualTo("broken-down");
    }

    @Test
    void tellsWhetherTheNewBusTookOverAtTheSamePlaceOrFurtherOn() {
        ScheduledTrip trip = new ScheduledTrip("t", "9", "009-01", "North", at("07:00"), at("07:20"),
                new int[] {0, 1, 2, 3, 4, 5, 6, 7}, new String[] {"A", "B", "C", "D", "E", "F", "G", "H"},
                new int[] {0, 120, 240, 360, 480, 600, 720, 840}, "S");
        Report stopped = new Report(1, "V1", at("07:03"), "9", "009-01", "North", 30, 2, 53.10, LON);
        Report last = new Report(1, "V1", at("07:05"), "9", "009-01", "North", 150, 2, 53.10, LON);
        // Joined heading to G, 1 km on: stops D–F unserved.
        Report far = new Report(2, "V2", at("07:15"), "9", "009-01", "North", 400, 6, 53.109, LON);
        var further = new TripAnomalies.Change(last, far, 6, stopped, 30, 400);
        assertThat(further.samePlace(trip)).isFalse();
        assertThat(further.skippedStops(trip)).isEqualTo(3);
        assertThat(further.standMinutes()).isEqualTo(12);
        // Heading to the next stop, or first seen next to the old bus: the same place.
        assertThat(new TripAnomalies.Change(last, far, 3, stopped, 30, 400).samePlace(trip)).isTrue();
        Report beside = new Report(2, "V2", at("07:15"), "9", "009-01", "North", 400, 6, 53.1005, LON);
        assertThat(new TripAnomalies.Change(last, beside, 6, stopped, 30, 400).skippedStops(trip)).isZero();
    }

    @Test
    void judgesAsSoonAsItIsClearWhatTheBusDid() {
        ScheduledTrip trip = trip("009-01", "North", "07:00");
        List<Report> finished = drive(1, trip, 0, 1, 2, 3, 4);
        // At 07:07, a minute before its scheduled arrival, the bus has reached the last stop: run in full.
        assertThat(judge(List.of(trip), finished, at("07:07")).values()).extracting(Outcome::kind)
                .containsExactly(Kind.COMPLETED);
        // At 07:03 it is halfway: pending, not lost or short.
        List<Report> halfway = finished.stream().filter(r -> r.time().isBefore(at("07:03"))).toList();
        assertThat(judge(List.of(trip), halfway, at("07:03")).values()).extracting(Outcome::kind)
                .containsExactly(Kind.PENDING);
        // Seen last at 07:02:40 and not since: lost only once 15 minutes have passed.
        assertThat(judge(List.of(trip), halfway, at("07:17")).values()).extracting(Outcome::kind)
                .containsExactly(Kind.PENDING);
        assertThat(judge(List.of(trip), halfway, at("07:18")).values()).extracting(Outcome::kind)
                .containsExactly(Kind.LOST);
    }

    @Test
    void matchesReportsByTheTimeAtTheirNextStop() {
        ScheduledTrip arriving = trip("009-01", "North", "07:00");
        ScheduledTrip leaving = trip("009-01", "South", "07:12");
        List<ScheduledTrip> brigade = List.of(arriving, leaving);
        // Waiting at the terminus at 07:09 for the 07:12 trip, under another headsign: the next trip, not the last.
        Report waiting = new Report(1, "V1", at("07:09"), "9", "009-01", "Południe", 0, 0, 53.112, LON);
        assertThat(TripAnomalies.match(waiting, brigade)).isSameAs(leaving);
        // Heading to stop 4 of the first trip at 07:07.
        assertThat(TripAnomalies.match(new Report(1, "V1", at("07:07"), "9", "009-01", "North", 0, 4, 53.11, LON),
                brigade)).isSameAs(arriving);
        // Ten minutes late: still the first trip, by its scheduled time.
        assertThat(TripAnomalies.match(new Report(1, "V1", at("07:16"), "9", "009-01", "North", 600, 3, 53.1, LON),
                brigade)).isSameAs(arriving);
        // Another line, or an hour away: nothing.
        assertThat(TripAnomalies.match(new Report(1, "V1", at("07:07"), "10", "009-01", "North", 0, 4, 0, 0),
                brigade)).isNull();
        assertThat(TripAnomalies.match(new Report(1, "V1", at("08:30"), "9", "009-01", "North", 0, 4, 0, 0),
                brigade)).isNull();
        assertThat(TripAnomalies.match(waiting, null)).isNull();
    }

    @Test
    void fillsBlankBrigadesFromTheSameBus() {
        List<Report> reports = List.of(
                new Report(1, "V1", at("07:00"), "9", "009-01", "North", 0, 1, 0, 0),
                new Report(1, "V1", at("07:01"), "9", "", "North", 0, 2, 0, 0),
                new Report(1, "V1", at("07:30"), "9", null, "North", 0, 3, 0, 0),
                new Report(1, "V1", at("07:33"), "9", "009-02", "North", 0, 3, 0, 0),
                new Report(2, "V2", at("07:34"), "9", null, "North", 0, 3, 0, 0));
        assertThat(TripAnomalies.withBrigades(reports)).extracting(Report::brigade)
                .containsExactly("009-01", "009-01", "009-02", "009-02", null);
    }

    @Test
    void measuresDistanceFromTheRouteAndGapsInRecording() {
        Shape shape = SHAPES.get("S");
        assertThat(TripAnomalies.distance(shape, 53.105, LON)).isLessThan(1);
        assertThat(TripAnomalies.distance(shape, 53.105, LON + 0.003)).isBetween(195.0, 205.0);
        assertThat(TripAnomalies.distance(shape, 53.115, LON)).as("past the end").isBetween(330.0, 340.0);
        // A tracker searching near its last segment gives the same answers.
        TripAnomalies.Tracker tracker = new TripAnomalies.Tracker(TripAnomalies.shape(List.of(
                new double[] {53.10, LON}, new double[] {53.105, LON}, new double[] {53.105, LON + 0.01})));
        assertThat(tracker.distance(53.102, LON)).isLessThan(1);
        assertThat(tracker.distance(53.106, LON + 0.008)).isBetween(105.0, 115.0);
        assertThat(TripAnomalies.distance(TripAnomalies.shape(List.<double[]>of(new double[] {53.1, LON})),
                53.101, LON)).isBetween(110.0, 112.0);

        assertThat(RECORDING.covers(at("09:00"), at("09:29"))).isTrue();
        assertThat(RECORDING.covers(at("09:25"), at("09:45"))).isFalse();
        Recording twoMissing = new Recording(Set.of(1L, 4L, 5L));
        assertThat(twoMissing.covers(java.time.Instant.ofEpochSecond(60), java.time.Instant.ofEpochSecond(300)))
                .as("2 minutes missing is not a gap").isTrue();

        ScheduledTrip t = trip("009-01", "North", "07:00");
        assertThat(t.index(2)).isEqualTo(2);
        assertThat(t.index(9)).isEqualTo(4);
        assertThat(t.scheduledAt(3)).isEqualTo(at("07:06"));
        assertThat(Kind.LOST.abnormal()).isTrue();
        assertThat(Kind.COMPLETED.abnormal()).isFalse();
        assertThat(Kind.PENDING.judged()).isFalse();
        assertThat(Set.of(Kind.values()).stream().map(k -> k.key).collect(Collectors.toSet()))
                .contains("short-turned", "no-signal");
    }
}
