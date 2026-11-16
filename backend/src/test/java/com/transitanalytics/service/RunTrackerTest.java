package com.transitanalytics.service;

import com.transitanalytics.dto.LineDetailDto.StopPoint;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.service.RunTracker.FullRun;
import com.transitanalytics.service.RunTracker.Sample;
import com.transitanalytics.service.RunTracker.Track;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RunTrackerTest {

    private static StopPoint stop(double km, String zone) {
        return new StopPoint("S" + km, 53.0, 23.0, km, zone);
    }

    private static Sample s(long vehicle, double t, String headsign, int seq) {
        return new Sample(vehicle, t, "103-01", headsign, seq);
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
    void splitsReportsIntoRunsAtHeadsignChangesAndGaps() {
        var samples = List.of(
                s(1, 0, "A", 0), s(1, 60, "A", 1), s(1, 120, "A", 2),
                s(1, 180, "B", 0), s(1, 240, "B", 1),
                s(1, 1000, "B", 3),                    // 13 min without a report: a new run
                s(2, 1060, "B", 0));                   // another bus
        List<Track> tracks = RunTracker.splitRuns(samples);

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
        double[] times = RunTracker.passTimes(track, km);

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

        assertThat(RunTracker.passTimes(joinedLate, km)).isNull();
        assertThat(RunTracker.passTimes(endedEarly, km)).isNull();
        assertThat(RunTracker.passTimes(otherVariant, km)).isNull();
        // Heading to the last-but-one stop is enough when the next run follows: the rest is passed before it starts.
        var switchedEarly = new Track("A", List.of(s(1, 0, "A", 0), s(1, 60, "A", 1)), 120.0);
        assertThat(RunTracker.passTimes(switchedEarly, km)).containsExactly(30.0, 75.0, 105.0);
        // No report after the run: the terminus is reached half a poll after the last report.
        assertThat(RunTracker.passTimes(whole, km)).containsExactly(15.0, 45.0, 90.0);
    }

    @Test
    void hourStatsHaveMinMaxMeanAndMedian() {
        var odd = RunTracker.hourStat(7, List.of(50.2, 41.0, 44.6), 0);
        assertThat(odd).isEqualTo(new LineDetailDto.HourStat(7, 3, 41, 50, 45, 45));
        var even = RunTracker.hourStat(8, List.of(10.0, 20.0, 30.0, 50.0), 1);
        assertThat(even.median()).isEqualTo(25.0);
        assertThat(even.avg()).isEqualTo(27.5);
    }

    @Test
    void segmentSpeedIsTotalDistanceOverTotalTime() {
        var stops = List.of(stop(0, "1"), stop(1, "1"), stop(3, "1"));
        var main = new LineDetailDto.Variant(0, "a", "A", "main", 3, 1, 10, 10, null, null, "S0", "S3",
                List.of(), stops);
        var runs = List.of(new FullRun(0, new double[]{0, 120, 360}), new FullRun(0, new double[]{0, 60, 300}));

        var profile = RunTracker.segmentProfile(main, runs);
        assertThat(profile.runs()).isEqualTo(2);
        // 2 km in 180 s → 40 km/h; 4 km in 480 s → 30 km/h.
        assertThat(profile.segments()).extracting(LineDetailDto.Segment::kmh).containsExactly(40.0, 30.0);
        assertThat(RunTracker.segmentProfile(main, List.of()).segments().get(0).kmh()).isNull();
    }

    @Test
    void runsMatchTheVariantWhoseStopNameFitsTheLastReport() {
        var base = variant("a", "main", "S0", "S1", "S2", "S3");
        var longer = variant("b", "longer", "S0", "S1", "S2", "S3", "Far");
        // Next stop index 3 is the last stop of the base route but only the last but one of the longer variant.
        var toS3 = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"), new Sample(1, 120, "x", "A", 1, "S1"),
                new Sample(1, 240, "x", "A", 3, "S3")), 300.0);
        assertThat(RunTracker.matchRun(toS3, List.of(longer, base)).variant()).isSameAs(base);
        var toFar = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"), new Sample(1, 120, "x", "A", 2, "S2"),
                new Sample(1, 240, "x", "A", 4, "Far")), 300.0);
        assertThat(RunTracker.matchRun(toFar, List.of(base, longer)).variant()).isSameAs(longer);
        // Same stop count, different stops: the reported name decides.
        var viaX = variant("c", "longer", "S0", "S1", "X2", "S3");
        var lastAtX = new Track("A", List.of(new Sample(1, 0, "x", "A", 0, "S0"),
                new Sample(1, 120, "x", "A", 1, "S1"), new Sample(1, 240, "x", "A", 3, "S3"),
                new Sample(1, 250, "x", "A", 2, "X2(512)")), 300.0);
        assertThat(RunTracker.matchRun(lastAtX, List.of(base, viaX)).variant()).isSameAs(viaX);
        var otherHeadsign = new Track("B", toFar.samples(), 300.0);
        assertThat(RunTracker.matchRun(otherHeadsign, List.of(base, longer))).isNull();
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

        var patterns = RunTracker.patterns(List.of(base0, long0, rare, base1, short1, turn));
        // Both patterns contain a base route; the one with more trips a day (22 vs 21) comes first. The rare variant
        // (1 trip) is left out, and the short turn to Piastowska joins no pattern.
        assertThat(patterns).containsExactly(List.of(base0, short1), List.of(long0, base1));
        assertThat(StopPlaces.sameStop("Kleosin/Pętla", "Kleosin/Pętla")).isTrue();
        assertThat(StopPlaces.sameStop("Centrum st. 1", "Centrum st. 4")).isTrue();
        assertThat(StopPlaces.sameStop("Rynek", "Nowy Rynek")).isFalse();
    }
}
