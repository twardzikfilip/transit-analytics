package com.transitanalytics.service;

import com.transitanalytics.dto.TripRunDto;
import com.transitanalytics.service.TripAnomalies.Report;
import com.transitanalytics.service.TripAnomalies.ScheduledTrip;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripRunServiceTest {

    private static final Instant T0 = Instant.parse("2099-01-05T06:00:00Z");
    /** Five stops northwards, about 330 m apart; the last at 53.112. */
    private static final ScheduledTrip TRIP = new ScheduledTrip("t", "103", "103-05", "Choroszcz", T0,
            T0.plusSeconds(600), new int[] {1, 2, 3, 4, 5}, new String[] {"A", "B", "C", "D", "Kościelna"},
            new int[] {0, 150, 300, 450, 600}, "S", new double[] {53.112, 23.15});

    private static Report report(long seconds, double lat, int next, Integer delay) {
        return new Report(1, "230", T0.plusSeconds(seconds), "103", "103-05", "Choroszcz", delay, next, lat, 23.15);
    }

    @Test
    void namesATripByItsBrigadeLineOrPartOfItsDestination() {
        assertThat(TripRunService.named(TRIP, "103-05")).isTrue();
        assertThat(TripRunService.named(TRIP, "103")).isTrue();
        assertThat(TripRunService.named(TRIP, "choro")).isTrue();
        assertThat(TripRunService.named(TRIP, "kośc")).as("the last stop").isTrue();
        assertThat(TripRunService.named(TRIP, "c")).as("one letter names nothing").isFalse();
        assertThat(TripRunService.named(TRIP, "104")).isFalse();
    }

    @Test
    void samplesTheDelayAndSpeedEveryMinute() {
        List<Report> reports = new ArrayList<>();
        // 10 s apart, 50 m each: 18 km/h.
        for (int i = 0; i < 13; i++) {
            reports.add(report(i * 10L, 53.100 + i * 0.00045, 2, i * 10));
        }
        reports.add(new Report(1, "230", T0.plusSeconds(135), "103", "103-05", null, 0, null, 53.2, 23.15));
        List<TripRunDto.Sample> samples = TripRunService.samples(TRIP, reports);
        assertThat(samples).extracting(TripRunDto.Sample::delay).containsExactly(0, 60, 120);
        assertThat(samples.get(0).kmh()).isNull();
        assertThat(samples.get(1).kmh()).isEqualTo(18);
        assertThat(samples.get(0).stop()).isEqualTo(2);
    }

    @Test
    void averagesTheSpeedOverTheTripLeavingOutGapsAndJumps() {
        List<Report> reports = new ArrayList<>();
        // 50 m every 10 s for 2 min (18 km/h), then 2 min standing: 600 m in 240 s, 9 km/h.
        for (int i = 0; i <= 12; i++) {
            reports.add(report(i * 10L, 53.100 + i * 0.00045, 2, 0));
        }
        reports.add(report(240, 53.100 + 12 * 0.00045, 3, 0));
        // Gone 10 min, then a GPS jump of 1 km in 10 s: neither counts.
        reports.add(report(840, 53.110, 4, 0));
        reports.add(report(850, 53.119, 4, 0));
        assertThat(TripRunService.averageKmh(reports)).isEqualTo(9);
        assertThat(TripRunService.averageKmh(List.of())).isNull();
    }

    @Test
    void tellsStandingFromDrivingAndHowLongTheBusStoodAtEachStop() {
        List<Report> reports = new ArrayList<>();
        // Driving 100 m every 10 s (36 km/h) to the stop at 53.1027, standing there 40 s with 2 m of GPS jitter,
        // then on again; a 3 min gap; then a jump of 1 km in 10 s.
        for (int i = 0; i <= 3; i++) {
            reports.add(report(i * 10L, 53.1 + i * 0.0009, 2, 0));
        }
        for (int i = 1; i <= 4; i++) {
            reports.add(report(30 + i * 10L, 53.1027 + (i % 2) * 0.00002, 2, 0));
        }
        for (int i = 1; i <= 3; i++) {
            reports.add(report(70 + i * 10L, 53.1027 + i * 0.0009, 3, 0));
        }
        reports.add(report(280, 53.1054, 3, 0));
        reports.add(report(290, 53.1144, 3, 0));
        var segments = TripRunService.segments(reports);
        Integer[] kmh = TripRunService.speeds(reports, segments);

        assertThat(kmh[0]).as("a bus's first report").isNull();
        assertThat(kmh[2]).isEqualTo(36);
        assertThat(java.util.Arrays.asList(kmh).subList(4, 8)).containsOnly(0);
        assertThat(kmh[9]).isEqualTo(36);
        assertThat(kmh[11]).as("after a gap").isNull();
        assertThat(kmh[12]).as("a GPS jump").isNull();
        assertThat(TripRunService.dwell(reports, segments, new double[] {53.1027, 23.15})).isEqualTo(40);
        assertThat(TripRunService.dwell(reports, segments, new double[] {53.1009, 23.15})).as("went past").isZero();
        assertThat(TripRunService.dwell(reports, segments, new double[] {53.2, 23.15})).as("never near").isNull();
    }

    @Test
    void aHaltShorterThanTheReportsApartStillCountsButCrawlingIsUnclear() {
        // 10 s apart: 100 m, then 15 m (pulled in, halted, pulled out: 5 km/h), then 100 m; the stop at the middle.
        List<Report> halt = List.of(report(0, 53.1, 2, 0), report(10, 53.1009, 2, 0),
                report(20, 53.101035, 3, 0), report(30, 53.101935, 3, 0));
        var segments = TripRunService.segments(halt);
        assertThat(TripRunService.dwell(halt, segments, new double[] {53.10097, 23.15})).isEqualTo(10);
        // 25 m in 10 s (9 km/h) near the stop: may have halted for a moment, or not.
        List<Report> crawl = List.of(report(0, 53.1, 2, 0), report(10, 53.1009, 2, 0),
                report(20, 53.101125, 3, 0), report(30, 53.102025, 3, 0));
        assertThat(TripRunService.dwell(crawl, TripRunService.segments(crawl), new double[] {53.10101, 23.15}))
                .isNull();
    }

    @Test
    void tellsWhenTheBusPassedEachStop() {
        // Heading to B from 06:00:30, to C from 06:03 (3 min late), then gone before D.
        List<Report> reports = List.of(report(30, 53.1, 2, 0), report(180, 53.103, 3, 180),
                report(200, 53.103, 3, 180));
        var run = new com.transitanalytics.dto.BrigadeDto.ActualRun("230", 1, 3, "A", "C", T0, T0.plusSeconds(300),
                0, 180);
        List<TripRunDto.StopTime> stops = TripRunService.stopTimes(TRIP, reports, List.of(run));
        assertThat(stops).extracting(TripRunDto.StopTime::name).containsExactly("A", "B", "C", "D", "Kościelna");
        assertThat(stops.get(1).scheduled()).isEqualTo(T0.plusSeconds(150));
        assertThat(stops).extracting(TripRunDto.StopTime::passed)
                .containsExactly(T0.plusSeconds(30), T0.plusSeconds(180), null, null, null);
        assertThat(stops.get(1).delay()).isEqualTo(180);
        // A run to the last stop: it got there on arrival.
        var full = new com.transitanalytics.dto.BrigadeDto.ActualRun("230", 1, 5, "A", "Kościelna", T0,
                T0.plusSeconds(700), 0, 100);
        assertThat(TripRunService.stopTimes(TRIP, reports, List.of(full)).get(4))
                .satisfies(s -> {
                    assertThat(s.passed()).isEqualTo(T0.plusSeconds(700));
                    assertThat(s.delay()).isEqualTo(100);
                });
    }

    @Test
    void keepsTheReportsOfEachBusWhileItRanItsPart() {
        List<Report> reports = List.of(report(0, 53.1, 1, 0), report(100, 53.1, 2, 0), report(300, 53.1, 3, 0));
        var run = new com.transitanalytics.dto.BrigadeDto.ActualRun("230", 1, 3, "A", "C", T0.plusSeconds(60),
                T0.plusSeconds(200), 0, 0);
        assertThat(TripRunService.running(reports, List.of(run))).extracting(Report::time)
                .containsExactly(T0.plusSeconds(100));
    }

    @Test
    void findsWhereABusStoodStillOnTheWayButNotAtTheEnds() {
        List<Report> reports = new ArrayList<>();
        // Waiting at the first stop (heading to stop 1) for 5 min: the timetable's.
        for (int i = 0; i <= 30; i++) {
            reports.add(report(i * 10L, 53.100, 1, 0));
        }
        // Standing 4 min heading to C, its delay growing by 3 min.
        for (int i = 0; i <= 24; i++) {
            reports.add(report(400 + i * 10L, 53.104, 3, 60 + i * 180 / 24));
        }
        // Moving on, then standing 5 min at the last stop.
        reports.add(report(700, 53.108, 4, 240));
        for (int i = 0; i <= 30; i++) {
            reports.add(report(800 + i * 10L, 53.1119, 5, 240));
        }
        List<TripRunDto.Stand> stands = TripRunService.stands(TRIP, reports);
        assertThat(stands).singleElement().satisfies(s -> {
            assertThat(s.minutes()).isEqualTo(4);
            assertThat(s.stop()).isEqualTo("C");
            assertThat(s.stopIndex()).isEqualTo(3);
            assertThat(s.delayGrowth()).isEqualTo(180);
            assertThat(s.bus()).isEqualTo("230");
        });
    }
}
