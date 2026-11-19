package com.transitanalytics.service;

import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.service.LineTimetable.TripRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.transitanalytics.service.LineFixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

class LineTimetableTest {

    private static TripRow trip(String duty, int startMin, int endMin, int dir, String shape) {
        return trip(duty, startMin, endMin, dir, shape, dir == 0 ? "A" : "Z", dir == 0 ? "Z" : "A");
    }

    private static TripRow trip(String duty, int startMin, int endMin, int dir, String shape, String from, String to) {
        return new TripRow(startMin, duty, dir, shape, "H" + dir, startMin * 60, endMin * 60, 10.0, 20, from, to);
    }

    private static StopPlaces.StopRef at(String name, double lat, double lon) {
        return new StopPlaces.StopRef(name, lat, lon);
    }

    @Test
    void zoneTwoCountsSegmentsInsideAndHalfOfThoseCrossingTheBorder() {
        var stops = List.of(stop(0, "1"), stop(2, "1"), stop(4, "2"), stop(7, "2"), stop(8, "2"));
        LineDetailDto.Zone2 zone = LineTimetable.zone2(0, "Choroszcz", "main", stops);
        // 2→4 crosses the border (half of 2 km), 4→7 and 7→8 are inside (4 km).
        assertThat(zone.km()).isEqualTo(5.0);
        assertThat(zone.stops()).isEqualTo(3);
        assertThat(zone.totalStops()).isEqualTo(5);
        assertThat(zone.totalKm()).isEqualTo(8.0);
    }

    @Test
    void zoneTwoIsNullForACityLine() {
        assertThat(LineTimetable.zone2(0, "x", "main", List.of(stop(0, "1"), stop(3, "1")))).isNull();
    }

    @Test
    void brigadesRunFromFirstDepartureToLastArrivalWithLongGapsAsBreaks() {
        var trips = List.of(
                trip("5-02", 300, 340, 0, "a"),
                trip("5-01", 240, 280, 0, "a"),
                trip("5-01", 290, 330, 1, "b"),          // 10 min layover: no break
                trip("5-01", 600, 640, 0, "long"),       // 4 h 30 later: a break
                trip("5-01", 1430, 1470, 1, "b"));       // past midnight (24:30)
        var brigades = LineTimetable.brigades(trips,
                Map.of(LineTimetable.variantKey(0, "long"), "longer"), Map.of(600L, "pull_out"));

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
        var stops = LineTimetable.endStops(trips, List.of(trips.get(0)));
        assertThat(stops).extracting(StopPlaces.Place::name).containsExactly("A", "M", "Z");
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
        var depot = LineTimetable.depotRuns(trips, LineTimetable.endStops(trips, List.of(trips.get(1))));
        assertThat(depot).containsOnly(
                Map.entry(290L, "pull_out"), Map.entry(350L, "pull_in"), Map.entry(560L, "pull_out"),
                Map.entry(785L, "pull_in"));
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
        var ends = LineTimetable.endStops(trips, List.of(trips.get(0), trips.get(1)));
        assertThat(ends).extracting(StopPlaces.Place::name).containsExactly("A", "Z");
        assertThat(LineTimetable.depotRuns(trips, ends)).containsOnly(Map.entry(5L, "pull_in"));
    }

    private static LineTimetable.TripRow located(long id, String headsign, double fromLat, double toLat) {
        return new LineTimetable.TripRow(id, "x", 0, "S", headsign, 0, 1800, 19.0, 18, "A", "B",
                new StopPlaces.StopRef("A", fromLat, 23.13), new StopPlaces.StopRef("B", toLat, 23.13));
    }

    @Test
    void givesTripsWithoutADirectionOneFromWhereTheyStart() {
        // Most go north (Supraśl), from 53.13 to 53.21; the others start up there and come back, or turn short.
        var trips = java.util.List.of(located(1, "Supraśl", 53.13, 53.21), located(2, "Supraśl", 53.13, 53.21),
                located(3, "Białystok", 53.21, 53.13), located(4, "Białystok", 53.20, 53.15),
                located(5, "Supraśl", 53.14, 53.20));
        assertThat(LineTimetable.withDirections(trips)).extracting(LineTimetable.TripRow::direction)
                .containsExactly(0, 0, 1, 1, 0);
        // Without stop positions nothing can be told: left as they are.
        var unknown = java.util.List.of(new LineTimetable.TripRow(1, "x", 0, "S", "H", 0, 60, 1.0, 2, "A", "B"));
        assertThat(LineTimetable.withDirections(unknown)).isEqualTo(unknown);
    }

    @Test
    void takesAStopsZoneFromTheFeedElseFromTheCityLimits() {
        assertThat(LineTimetable.zone("1", 53.21, 23.33)).isEqualTo("1");
        assertThat(LineTimetable.zone(null, 53.13, 23.16)).as("Rynek Kościuszki").isEqualTo("1");
        assertThat(LineTimetable.zone(null, 53.21, 23.33)).as("Supraśl").isEqualTo("2");
        assertThat(LineTimetable.zone(null, null, null)).isNull();
    }
}
