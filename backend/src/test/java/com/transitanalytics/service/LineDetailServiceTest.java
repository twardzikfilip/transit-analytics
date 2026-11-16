package com.transitanalytics.service;

import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.service.LineDetailService.FullRun;
import com.transitanalytics.service.LineDetailService.Sample;
import com.transitanalytics.service.LineDetailService.Track;
import com.transitanalytics.service.LineDetailService.TripRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LineDetailServiceTest {

    @Test
    void classifiesSubtypeByLineName() {
        assertThat(RouteSubtype.classify("1")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("50")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("99")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("100")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("142")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("1000")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("N1")).isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(RouteSubtype.classify("Z")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify(null)).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.of("night_urban")).isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(RouteSubtype.SUBURBAN.value()).isEqualTo("suburban");
    }

    @Test
    void stripsTheStopCodeFromNames() {
        assertThat(LineDetailService.stopName("Lodowa/Chłodna(654)")).isEqualTo("Lodowa/Chłodna");
        assertThat(LineDetailService.stopName("Koplany/Cegielnia NŻ (911)")).isEqualTo("Koplany/Cegielnia NŻ");
        assertThat(LineDetailService.stopName("Rynek")).isEqualTo("Rynek");
    }

    private static StopPoint stop(double km, String zone) {
        return new StopPoint("S" + km, 53.0, 23.0, km, zone);
    }

    @Test
    void zoneTwoCountsSegmentsInsideAndHalfOfThoseCrossingTheBorder() {
        var stops = List.of(stop(0, "1"), stop(2, "1"), stop(4, "2"), stop(7, "2"), stop(8, "2"));
        LineDetailDto.Zone2 zone = LineDetailService.zone2(0, "Choroszcz", "main", stops);
        // 2→4 crosses the border (half of 2 km), 4→7 and 7→8 are inside (4 km).
        assertThat(zone.km()).isEqualTo(5.0);
        assertThat(zone.stops()).isEqualTo(3);
        assertThat(zone.totalStops()).isEqualTo(5);
        assertThat(zone.totalKm()).isEqualTo(8.0);
    }

    @Test
    void zoneTwoIsNullForACityLine() {
        assertThat(LineDetailService.zone2(0, "x", "main", List.of(stop(0, "1"), stop(3, "1")))).isNull();
    }

    private static TripRow trip(String duty, int startMin, int endMin, int dir, String shape) {
        return trip(duty, startMin, endMin, dir, shape, dir == 0 ? "A" : "Z", dir == 0 ? "Z" : "A");
    }

    private static TripRow trip(String duty, int startMin, int endMin, int dir, String shape, String from, String to) {
        return new TripRow(startMin, duty, dir, shape, "H" + dir, startMin * 60, endMin * 60, 10.0, 20, from, to);
    }

    @Test
    void brigadesRunFromFirstDepartureToLastArrivalWithLongGapsAsBreaks() {
        var trips = List.of(
                trip("5-02", 300, 340, 0, "a"),
                trip("5-01", 240, 280, 0, "a"),
                trip("5-01", 290, 330, 1, "b"),          // 10 min layover: no break
                trip("5-01", 600, 640, 0, "long"),       // 4 h 30 later: a break
                trip("5-01", 1430, 1470, 1, "b"));       // past midnight (24:30)
        var brigades = LineDetailService.brigades(trips,
                Map.of(LineDetailService.variantKey(0, "long"), "longer"), Map.of(600L, "pull_out"));

        assertThat(brigades).extracting(LineDetailDto.Brigade::name).containsExactly("5-01", "5-02");
        var b = brigades.get(0);
        assertThat(b.start()).isEqualTo(240 * 60);
        assertThat(b.end()).isEqualTo(1470 * 60);
        assertThat(b.runs()).hasSize(4);
        assertThat(b.runs().get(2).kind()).isEqualTo("longer");
        assertThat(b.runs().get(0).kind()).isEqualTo("main");
        assertThat(b.runs().get(2).depot()).isEqualTo("pull_out");
        assertThat(b.runs().get(1).depot()).isNull();
        assertThat(b.breaks()).containsExactly(new LineDetailDto.Break(330 * 60, 600 * 60),
                new LineDetailDto.Break(640 * 60, 1430 * 60));
    }

    private static Sample s(long vehicle, double t, String headsign, int seq) {
        return new Sample(vehicle, t, "103-01", headsign, seq);
    }

    @Test
    void splitsReportsIntoRunsAtHeadsignChangesAndGaps() {
        var samples = List.of(
                s(1, 0, "A", 0), s(1, 60, "A", 1), s(1, 120, "A", 2),
                s(1, 180, "B", 0), s(1, 240, "B", 1),
                s(1, 1000, "B", 3),                    // 13 min without a report: a new run
                s(2, 1060, "B", 0));                   // another bus
        List<Track> tracks = LineDetailService.splitRuns(samples);

        assertThat(tracks).extracting(t -> t.samples().size()).containsExactly(3, 2, 1, 1);
        assertThat(tracks.get(0).after()).isEqualTo(180.0);
        assertThat(tracks.get(1).after()).isNull();
        assertThat(tracks.get(2).after()).isNull();
    }

    @Test
    void passTimesUseMidpointsAndSpreadSkippedStopsByDistance() {
        double[] km = {0, 1, 2, 4, 5};
        var track = new Track("A", List.of(
                s(1, 0, "A", 0), s(1, 60, "A", 0),     // waiting at the first stop
                s(1, 120, "A", 1),                     // left stop 0 between 60 and 120
                s(1, 180, "A", 4)),                    // passed stops 1, 2 and 3 between 120 and 180
                300.0);                                // next report (next run) at 300
        double[] times = LineDetailService.passTimes(track, km);

        assertThat(times[0]).isEqualTo(90.0);
        // Stops 1, 2, 3 at km 1, 2, 4: span 3 km, padding 1.5 km → (0.75, 1.75, 3.75) / 4.5 of the gap.
        assertThat(times[1]).isCloseTo(130.0, within(1e-9));
        assertThat(times[2]).isCloseTo(143.333, within(1e-3));
        assertThat(times[3]).isCloseTo(170.0, within(1e-9));
        assertThat(times[4]).isEqualTo(240.0);
    }

    @Test
    void onlyRunsCoveringTheWholeRouteHavePassTimes() {
        double[] km = {0, 1, 2};
        var joinedLate = new Track("A", List.of(s(1, 0, "A", 1), s(1, 60, "A", 2)), null);
        var endedEarly = new Track("A", List.of(s(1, 0, "A", 0), s(1, 60, "A", 1)), null);
        var otherVariant = new Track("A", List.of(s(1, 0, "A", 0), s(1, 60, "A", 3)), null);
        var whole = new Track("A", List.of(s(1, 0, "A", 0), s(1, 60, "A", 2)), null);

        assertThat(LineDetailService.passTimes(joinedLate, km)).isNull();
        assertThat(LineDetailService.passTimes(endedEarly, km)).isNull();
        assertThat(LineDetailService.passTimes(otherVariant, km)).isNull();
        // Heading to the last-but-one stop is enough when the next run follows: the rest is passed before it starts.
        var switchedEarly = new Track("A", List.of(s(1, 0, "A", 0), s(1, 60, "A", 1)), 120.0);
        assertThat(LineDetailService.passTimes(switchedEarly, km)).containsExactly(30.0, 75.0, 105.0);
        // No report after the run: the terminus is reached half a poll after the last report.
        assertThat(LineDetailService.passTimes(whole, km)).containsExactly(15.0, 45.0, 90.0);
    }

    @Test
    void hourStatsHaveMinMaxMeanAndMedian() {
        var odd = LineDetailService.hourStat(7, List.of(50.2, 41.0, 44.6), 0);
        assertThat(odd).isEqualTo(new LineDetailDto.HourStat(7, 3, 41, 50, 45, 45));
        var even = LineDetailService.hourStat(8, List.of(10.0, 20.0, 30.0, 50.0), 1);
        assertThat(even.median()).isEqualTo(25.0);
        assertThat(even.avg()).isEqualTo(27.5);
    }

    @Test
    void segmentSpeedIsTotalDistanceOverTotalTime() {
        var stops = List.of(stop(0, "1"), stop(1, "1"), stop(3, "1"));
        var main = new LineDetailDto.Variant(0, "a", "A", "main", 3, 1, 10, 10, null, null, "S0", "S3",
                List.of(), stops);
        var runs = List.of(new FullRun(0, new double[]{0, 120, 360}), new FullRun(0, new double[]{0, 60, 300}));

        var profile = LineDetailService.segmentProfile(main, runs);
        assertThat(profile.runs()).isEqualTo(2);
        // 2 km in 180 s → 40 km/h; 4 km in 480 s → 30 km/h.
        assertThat(profile.segments()).extracting(LineDetailDto.Segment::kmh).containsExactly(40.0, 30.0);
        assertThat(LineDetailService.segmentProfile(main, List.of()).segments().get(0).kmh()).isNull();
    }

    @Test
    void serviceHoursAfterMidnightContinueTheDay() {
        // 2026-11-16 23:30 and 2026-11-17 01:30 in Warsaw (UTC+1).
        assertThat(LineDetailService.serviceHour(1_794_868_200L)).isEqualTo(23);
        assertThat(LineDetailService.serviceHour(1_794_875_400L)).isEqualTo(25);
        assertThat(LineDetailService.serviceDay(1_794_875_400L)).isEqualTo(java.time.LocalDate.of(2026, 11, 16));
    }

    @Test
    void endStopsAreWhereBusesTurnRoundInService() {
        var trips = List.of(
                trip("1", 300, 340, 0, "a", "A", "Z"),
                trip("1", 345, 385, 1, "b", "Z", "A"),    // 5 min at Z: an end stop
                trip("1", 460, 500, 0, "a", "A", "Z"),    // 75 min at A: a long break, A stays an end stop
                trip("1", 505, 520, 1, "s", "Z", "M"),    // short turn to M, back 5 min later: M is an end stop too
                trip("1", 525, 540, 0, "s2", "M", "Z"),
                trip("2", 290, 300, 0, "d", "Depot", "A"),
                trip("2", 305, 345, 0, "a", "A", "Z"),
                trip("2", 350, 380, 1, "p", "Z", "Garage"));
        var stops = LineDetailService.endStops(trips, List.of(trips.get(0)));
        assertThat(stops).extracting(LineDetailService.Place::name).containsExactly("A", "M", "Z");
    }

    @Test
    void depotRunsLeaveOrReachTheDepotAroundTheDayAndLongBreaks() {
        var trips = List.of(
                trip("1", 290, 300, 0, "d", "Depot", "A"),     // first run, not from an end stop: pull-out
                trip("1", 305, 345, 0, "a", "A", "Z"),
                trip("1", 350, 380, 1, "p", "Z", "Garage"),    // then a 3 h break: pull-in
                trip("1", 560, 575, 0, "d2", "Garage", "Z"),   // after the break: pull-out
                trip("1", 580, 620, 1, "b", "Z", "A"),
                trip("1", 700, 740, 0, "a", "A", "Z"),         // long wait at A: just a break
                trip("1", 745, 760, 1, "s", "Z", "M"),         // short turn, back 5 min later: not a depot run
                trip("1", 765, 780, 0, "s2", "M", "Z"),
                trip("1", 785, 800, 1, "e", "Z", "Garage"));   // last run: pull-in
        var depot = LineDetailService.depotRuns(trips, LineDetailService.endStops(trips, List.of(trips.get(1))));
        assertThat(depot).containsOnly(
                Map.entry(290L, "pull_out"), Map.entry(350L, "pull_in"), Map.entry(560L, "pull_out"),
                Map.entry(785L, "pull_in"));
    }

    private static LineDetailDto.Variant variant(String shape, String kind, String... stopNames) {
        var stops = new java.util.ArrayList<StopPoint>();
        for (int i = 0; i < stopNames.length; i++) {
            stops.add(new StopPoint(stopNames[i], 53.0, 23.0, i, "1"));
        }
        return new LineDetailDto.Variant(0, shape, "A", kind, stopNames.length - 1, 1, 1, 10, null, null,
                stopNames[0], stopNames[stopNames.length - 1], List.of(), stops);
    }

    @Test
    void runsMatchTheVariantWhoseStopNameFitsTheLastReport() {
        var base = variant("a", "main", "S0", "S1", "S2", "S3");
        var longer = variant("b", "longer", "S0", "S1", "S2", "S3", "Far");
        // Next stop index 3 is the last stop of the base route but only the last but one of the longer variant.
        var toS3 = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"), new Sample(1, 120, "x", "A", 1, "S1"),
                new Sample(1, 240, "x", "A", 3, "S3")), 300.0);
        assertThat(LineDetailService.matchRun(toS3, List.of(longer, base)).variant()).isSameAs(base);
        var toFar = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"), new Sample(1, 120, "x", "A", 2, "S2"),
                new Sample(1, 240, "x", "A", 4, "Far")), 300.0);
        assertThat(LineDetailService.matchRun(toFar, List.of(base, longer)).variant()).isSameAs(longer);
        // Same stop count, different stops: the reported name decides.
        var viaX = variant("c", "longer", "S0", "S1", "X2", "S3");
        var lastAtX = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"),
                new Sample(1, 120, "x", "A", 1, "S1"), new Sample(1, 240, "x", "A", 3, "S3"),
                new Sample(1, 250, "x", "A", 2, "X2(512)")), 300.0);
        assertThat(LineDetailService.matchRun(lastAtX, List.of(base, viaX)).variant()).isSameAs(viaX);
        var otherHeadsign = new Track("B", toFar.samples(), 300.0);
        assertThat(LineDetailService.matchRun(otherHeadsign, List.of(base, longer))).isNull();
    }

    @Test
    void vehicleUseCountsDaysAndFindsBusChangesWithinABrigadeDay() {
        java.time.LocalDate d1 = java.time.LocalDate.of(2026, 11, 9);
        java.time.LocalDate d2 = java.time.LocalDate.of(2026, 11, 10);
        java.time.Instant t = java.time.Instant.parse("2026-11-09T06:00:00Z");
        var rows = List.of(
                new LineDetailService.VehicleDay(d1, "103-01", "224", "Solaris", t, t.plusSeconds(36000), 600),
                new LineDetailService.VehicleDay(d1, "103-01", "221", "Solaris", t.plusSeconds(36400),
                        t.plusSeconds(44000), 140),
                new LineDetailService.VehicleDay(d2, "103-01", "224", "Solaris", t, t.plusSeconds(3600), 60),
                new LineDetailService.VehicleDay(d2, "103-02", "300", "MAN", t, t.plusSeconds(3600), 60),
                new LineDetailService.VehicleDay(d2, "103-02", "301", "MAN", t, t.plusSeconds(120), 2),  // a glitch
                new LineDetailService.VehicleDay(d2, "", "999", null, t, t.plusSeconds(600), 10));
        var use = LineDetailService.vehicleUse(rows);

        assertThat(use.days()).isEqualTo(2);
        assertThat(use.vehicles()).extracting(LineDetailDto.VehicleUse::number).containsExactly("224", "221", "300", "999");
        assertThat(use.vehicles().get(0).days()).isEqualTo(2);
        assertThat(use.vehicles().get(3).brigades()).isEmpty();
        assertThat(use.brigades()).containsExactly(
                new LineDetailDto.BrigadeVehicles("103-01", "224", 2, 2, 2),
                new LineDetailDto.BrigadeVehicles("103-02", "300", 1, 1, 1));
        assertThat(use.switches()).hasSize(1);
        assertThat(use.switches().get(0).spells()).extracting(LineDetailDto.VehicleSpell::number)
                .containsExactly("224", "221");
    }

    @Test
    void patternsJoinBothDirectionsByTheirEnds() {
        var base0 = new LineDetailDto.Variant(0, "a", "Stadion", "main", 12, 11, 38, 39, null, null,
                "Centrum st. 1", "Chorten Arena", List.of(), List.of());
        var long0 = new LineDetailDto.Variant(0, "b", "Juchnowiec", "longer", 25, 10, 58, 66, null, null,
                "Centrum st. 1", "Juchnowiec", List.of(), List.of());
        var rare = new LineDetailDto.Variant(0, "c", "Juchnowiec", "longer", 13, 1, 24, 24, null, null,
                "Chorten Arena", "Juchnowiec", List.of(), List.of());
        var base1 = new LineDetailDto.Variant(1, "d", "Centrum", "main", 25, 11, 56, 64, null, null,
                "Juchnowiec", "Centrum st. 4", List.of(), List.of());
        var short1 = new LineDetailDto.Variant(1, "e", "Centrum", "shorter", 12, 11, 32, 37, null, null,
                "Ciołkowskiego/Chorten Arena", "Centrum st. 4", List.of(), List.of());
        var turn = new LineDetailDto.Variant(1, "f", "Centrum", "shorter", 6, 4, 20, 21, null, null,
                "Piastowska", "Centrum st. 4", List.of(), List.of());

        var patterns = LineDetailService.patterns(List.of(base0, long0, rare, base1, short1, turn));
        // Both patterns contain a base route; the one with more trips a day (22 vs 21) comes first. The rare variant
        // (1 trip) is left out, and the short turn to Piastowska joins no pattern.
        assertThat(patterns).containsExactly(List.of(base0, short1), List.of(long0, base1));
        assertThat(LineDetailService.sameStop("Kleosin/Pętla", "Kleosin/Pętla")).isTrue();
        assertThat(LineDetailService.sameStop("Centrum st. 1", "Centrum st. 4")).isTrue();
        assertThat(LineDetailService.sameStop("Rynek", "Nowy Rynek")).isFalse();
    }

    private static LineDetailService.StopRef at(String name, double lat, double lon) {
        return new LineDetailService.StopRef(name, lat, lon);
    }

    @Test
    void stopsCloserThan500MetresAreOnePlaceNamedWhereRunsEnd() {
        // Centrum st. 1 (departures) and st. 4 (arrivals) are ~55 m apart; Juchnowiec is far away.
        var st1 = at("Centrum st. 1", 53.13350, 23.14400);
        var st4 = at("Centrum st. 4", 53.13370, 23.14470);
        var juch = at("Juchnowiec", 53.04000, 23.21000);
        var places = LineDetailService.places(List.of(st4, st4, juch), List.of(st1, st1, st1, juch));

        assertThat(places).extracting(LineDetailService.Place::name).containsExactly("Centrum st. 4", "Juchnowiec");
        assertThat(LineDetailService.atPlace(places, at("Centrum st. 1", 53.13350, 23.14400))).isTrue();
        // A stop nearby that isn't one of the place's stops is somewhere else.
        assertThat(LineDetailService.atPlace(places, at("Elsewhere", 53.13775, 23.14470))).isFalse();
        // Near (450 m from st. 4) joins; Far is 350 m from Near but 800 m from st. 4, so it stays apart.
        var near = at("Near", 53.13775, 23.14470);
        var far = at("Far", 53.14090, 23.14470);
        assertThat(LineDetailService.places(List.of(st4, near, far), List.of(st1)))
                .extracting(LineDetailService.Place::name).containsExactly("Centrum st. 4", "Far");
        assertThat(LineDetailService.meters(53.13370, 23.14470, 53.13775, 23.14470)).isBetween(440.0, 460.0);
    }

    @Test
    void depotRunsAreJudgedByTheEndStopsThemselvesNotByStopsNearby() {
        // Runs arrive at A and leave from its departure platform A2 (100 m away): one end stop, named A. Brigade 2
        // starts its day at A2, so that is no depot run. Its last run ends at "Depot", 480 m from Z but not an end
        // stop: a pull-in.
        var a = at("A", 53.10000, 23.10000);
        var a2 = at("A2", 53.10090, 23.10000);
        var z = at("Z", 53.20000, 23.20000);
        var depotStop = at("Depot", 53.20430, 23.20000);
        var trips = List.of(
                new TripRow(1, "1", 0, "a", "Z", 300 * 60, 340 * 60, 10.0, 9, "A2", "Z", a2, z),
                new TripRow(2, "1", 1, "b", "A", 345 * 60, 385 * 60, 10.0, 9, "Z", "A", z, a),
                new TripRow(3, "1", 0, "a", "Z", 390 * 60, 430 * 60, 10.0, 9, "A2", "Z", a2, z),
                new TripRow(4, "2", 0, "a", "Z", 400 * 60, 440 * 60, 10.0, 9, "A2", "Z", a2, z),
                new TripRow(5, "2", 1, "d", "Depot", 445 * 60, 470 * 60, 10.0, 9, "Z", "Depot", z, depotStop));
        var ends = LineDetailService.endStops(trips, List.of(trips.get(0), trips.get(1)));
        assertThat(ends).extracting(LineDetailService.Place::name).containsExactly("A", "Z");
        assertThat(LineDetailService.depotRuns(trips, ends)).containsOnly(Map.entry(5L, "pull_in"));
    }
}
