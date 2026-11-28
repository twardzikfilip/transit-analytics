package com.transitanalytics.hotspots;

import com.transitanalytics.dto.HotspotDto.Approach;
import com.transitanalytics.dto.HotspotDto.Hotspot;
import com.transitanalytics.dto.HotspotDto.RouteTime;
import com.transitanalytics.hotspots.StandstillHotspots.Standstill;
import com.transitanalytics.hotspots.StandstillHotspots.StopPoint;
import com.transitanalytics.timetable.StopPlaces;
import com.transitanalytics.timetable.TimetableMath;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandstillHotspotsTest {

    /** Metres to degrees of latitude, near Białystok. */
    private static final double M = 1 / 111_320.0;
    private static final double LAT = 53.13;
    private static final double LON = 23.16;
    /** 07:30 local time (UTC+1 in November). */
    private static final Instant MORNING = Instant.parse("2026-11-16T06:30:00Z");

    private static Standstill at(long vehicle, String route, double northMeters, double seconds, Integer bearing) {
        return new Standstill(vehicle, route, MORNING, seconds, LAT + northMeters * M, LON, bearing);
    }

    @Test
    void leavesOutStandstillsAtStopsAndNearTerminals() {
        List<StopPoint> stops = List.of(new StopPoint("Rynek(101)", LAT, LON, false),
                new StopPoint("Pętla(900)", LAT + 1000 * M, LON, true));
        List<Standstill> standstills = List.of(
                at(1, "9", 40, 30, 0),     // boarding at Rynek
                at(2, "9", 60, 30, 0),     // waiting at the lights past it
                at(3, "9", 880, 30, 0),    // 120 m before the terminal: a layover
                at(4, "9", 500, 30, 0));   // in between

        assertThat(StandstillHotspots.awayFromStops(standstills, stops)).extracting(Standstill::vehicleId)
                .containsExactly(2L, 4L);
    }

    @Test
    void clustersDenseStandstillsAndLeavesOutScatteredOnes() {
        List<Standstill> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(at(i, "9", i * 4, 40, 90));           // a queue 20 m long
            points.add(at(i, "10", 300 + i * 3, 40, 180));   // a second place
        }
        points.add(at(9, "9", 150, 40, 90));                 // on its own

        List<List<Standstill>> clusters = StandstillHotspots.clusters(points);

        assertThat(clusters).hasSize(2).allSatisfy(c -> assertThat(c).hasSize(6));
    }

    /** Every pair compared: cores joined to the cores near them, border points to the first cluster reaching them. */
    private static List<List<Standstill>> comparingAll(List<Standstill> points) {
        int[] label = new int[points.size()];
        int clusters = 0;
        for (int i = 0; i < points.size(); i++) {
            if (label[i] != 0 || near(points, i).size() < StandstillHotspots.MIN_POINTS) {
                continue;
            }
            clusters++;
            List<Integer> queue = new ArrayList<>(List.of(i));
            while (!queue.isEmpty()) {
                int j = queue.remove(queue.size() - 1);
                if (label[j] != 0) {
                    continue;
                }
                label[j] = clusters;
                List<Integer> more = near(points, j);
                if (more.size() >= StandstillHotspots.MIN_POINTS) {
                    queue.addAll(more);
                }
            }
        }
        List<List<Standstill>> result = new ArrayList<>();
        for (int c = 1; c <= clusters; c++) {
            List<Standstill> cluster = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                if (label[i] == c) {
                    cluster.add(points.get(i));
                }
            }
            result.add(cluster);
        }
        return result;
    }

    private static List<Integer> near(List<Standstill> points, int i) {
        List<Integer> near = new ArrayList<>();
        for (int j = 0; j < points.size(); j++) {
            if (StopPlaces.meters(points.get(i).lat(), points.get(i).lon(), points.get(j).lat(), points.get(j).lon())
                    <= StandstillHotspots.EPS_METERS) {
                near.add(j);
            }
        }
        return near;
    }

    @Test
    void clustersAsComparingAllPairsDoes() {
        java.util.Random random = new java.util.Random(11);
        for (int round = 0; round < 20; round++) {
            List<Standstill> points = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                points.add(new Standstill(i, "9", MORNING, 30, LAT + random.nextInt(60) * 5 * M,
                        LON + random.nextInt(60) * 5 * M, null));
            }
            assertThat(StandstillHotspots.clusters(points)).isEqualTo(comparingAll(points));
        }
    }

    @Test
    void ranksHotspotsByTimeLostAndDescribesThem() {
        List<Standstill> points = new ArrayList<>();
        // Lights: 3 buses, 9 waits of 30–90 s, mostly heading east, on routes 9 and 10.
        for (int i = 0; i < 9; i++) {
            points.add(at(i % 3, i < 6 ? "9" : "10", i, 30 + i * 7.5, i < 7 ? 90 : 0));
        }
        // A junction 500 m north with longer waits altogether.
        for (int i = 0; i < 6; i++) {
            points.add(at(10 + i, "4", 500 + i, 120, 180));
        }
        // One bus waiting again and again in a third place: not a hotspot.
        for (int i = 0; i < 6; i++) {
            points.add(at(20, "5", 1000, 60, 0));
        }
        List<StopPoint> stops = List.of(new StopPoint("Rynek(101)", LAT - 100 * M, LON, false));

        List<Hotspot> hotspots = StandstillHotspots.hotspots(points, stops, 2);

        assertThat(hotspots).extracting(Hotspot::id).containsExactly(1, 2);
        assertThat(hotspots).extracting(Hotspot::hours).containsExactly(0.2, 0.15);
        Hotspot lights = hotspots.get(1);
        assertThat(lights.standstills()).isEqualTo(9);
        assertThat(lights.vehicles()).isEqualTo(3);
        assertThat(lights.minutesPerDay()).as("9 min over 2 days").isEqualTo(4.5);
        assertThat(lights.meanSeconds()).isEqualTo(60);
        assertThat(lights.medianSeconds()).isEqualTo(60);
        assertThat(lights.p90Seconds()).isEqualTo(90);
        assertThat(lights.nearestStop()).as("without its code").isEqualTo("Rynek");
        assertThat(lights.nearestStopMeters()).isBetween(104, 106);
        assertThat(lights.routes()).extracting(RouteTime::route).containsExactly("9", "10");
        assertThat(lights.approaches()).extracting(Approach::heading).containsExactly("E", "N");
        assertThat(lights.peakHour()).isEqualTo(7);
        assertThat(lights.radiusMeters()).isBetween(3, 8);
    }

    @Test
    void namesCompassHeadingsAndPercentiles() {
        assertThat(StandstillHotspots.heading(0)).isZero();
        assertThat(StandstillHotspots.heading(22)).isZero();
        assertThat(StandstillHotspots.heading(23)).isEqualTo(1);
        assertThat(StandstillHotspots.heading(350)).isZero();
        assertThat(StandstillHotspots.heading(270)).isEqualTo(6);
        assertThat(TimetableMath.percentile(new double[0], 0.9)).isZero();
        assertThat(TimetableMath.percentile(new double[] {10, 20, 30, 40}, 0.5)).isEqualTo(20);
    }
}
