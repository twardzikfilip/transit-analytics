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
        LineDetailDto.Zone2 zone = LineDetailService.zone2(0, "Choroszcz", stops);
        // 2→4 crosses the border (half of 2 km), 4→7 and 7→8 are inside (4 km).
        assertThat(zone.km()).isEqualTo(5.0);
        assertThat(zone.stops()).isEqualTo(3);
        assertThat(zone.totalStops()).isEqualTo(5);
        assertThat(zone.totalKm()).isEqualTo(8.0);
    }

    @Test
    void zoneTwoIsNullForACityLine() {
        assertThat(LineDetailService.zone2(0, "x", List.of(stop(0, "1"), stop(3, "1")))).isNull();
    }

    private static TripRow trip(String duty, int startMin, int endMin, int dir, String shape) {
        return new TripRow(startMin, duty, dir, shape, "H" + dir, startMin * 60, endMin * 60, 10.0, 20);
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
                Map.of(LineDetailService.variantKey(0, "long"), "longer"));

        assertThat(brigades).extracting(LineDetailDto.Brigade::name).containsExactly("5-01", "5-02");
        var b = brigades.get(0);
        assertThat(b.start()).isEqualTo(240 * 60);
        assertThat(b.end()).isEqualTo(1470 * 60);
        assertThat(b.runs()).hasSize(4);
        assertThat(b.runs().get(2).kind()).isEqualTo("longer");
        assertThat(b.runs().get(0).kind()).isEqualTo("main");
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
        var main = new LineDetailDto.Variant(0, "a", "A", "main", 3, 1, 10, 10, "S0", "S3", List.of(), stops);
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
}
