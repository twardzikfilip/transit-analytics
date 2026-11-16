package com.transitanalytics.service;

import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.service.CorridorBuilder.TripStops;
import com.transitanalytics.service.StopPlaces.StopRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorridorServiceTest {
    private final CorridorFixtures fixtures = new CorridorFixtures();

    @Test
    void twoLinesSharingFiveStopsMakeOneCorridor() {
        var trips = List.of(fixtures.trip("A", 360, 10, 1, 2, 3, 4, 5, 11),
                fixtures.trip("B", 365, 20, 1, 2, 3, 4, 5, 21));
        var corridors = CorridorService.corridors(trips, CorridorFixtures.stops());

        assertThat(corridors).singleElement().satisfies(c -> {
            assertThat(c.stops()).containsExactly("S1", "S2", "S3", "S4", "S5");
            assertThat(c.lines()).containsExactly("A", "B");
            assertThat(c.throughLines()).containsExactly("A", "B");
            assertThat(c.km()).isEqualTo(2.0);
            assertThat(c.buses()).isEqualTo(2);
            assertThat(c.oneWay()).isTrue();
        });
    }

    @Test
    void aShorterCorridorInsideALongerOneStaysOnlyWithClearlyMoreBuses() {
        // A and B run 1–6 twice each (4 buses); C joins on 2–5 once: 5 buses there, less than 1.5 × 4 → dropped.
        // The 1–6 row still lists C, as running part of it.
        var trips = new ArrayList<>(List.of(
                fixtures.trip("A", 360, 1, 2, 3, 4, 5, 6), fixtures.trip("A", 380, 1, 2, 3, 4, 5, 6),
                fixtures.trip("B", 365, 1, 2, 3, 4, 5, 6), fixtures.trip("B", 385, 1, 2, 3, 4, 5, 6),
                fixtures.trip("C", 370, 30, 2, 3, 4, 5, 31)));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).singleElement().satisfies(c -> {
            assertThat(c.lines()).containsExactly("A", "B", "C");
            assertThat(c.throughLines()).containsExactly("A", "B");
        });
        // Two more C trips: 7 buses on 2–5, at least 1.5 × 4 → it stays as its own row.
        trips.add(fixtures.trip("C", 390, 30, 2, 3, 4, 5, 31));
        trips.add(fixtures.trip("C", 400, 30, 2, 3, 4, 5, 31));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).extracting(c -> c.stops().size())
                .containsExactlyInAnyOrder(6, 4);
    }

    @Test
    void corridorsJoinEndToEndWhereTheWayOnDoesNotFork() {
        // A and B share 1–5, B and C share 4–8: one corridor 1–8; only B runs all of it.
        var trips = List.of(
                fixtures.trip("A", 360, 10, 1, 2, 3, 4, 5, 11),
                fixtures.trip("B", 365, 20, 1, 2, 3, 4, 5, 6, 7, 8, 21),
                fixtures.trip("C", 370, 30, 4, 5, 6, 7, 8, 31));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).singleElement().satisfies(c -> {
            assertThat(c.stops()).containsExactly("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8");
            assertThat(c.lines()).containsExactly("A", "B", "C");
            assertThat(c.throughLines()).containsExactly("B");
            // Buses per stretch: 2, 2, 2, 3 (A, B and C on 4–5), 2, 2, 2 → 15 / 7 ≈ 2.
            assertThat(c.buses()).isEqualTo(2);
            assertThat(c.km()).isEqualTo(3.5);
        });
    }

    @Test
    void joiningStopsWhereTheWayOnForks() {
        // After 1–5 (A and B), B and C go on to 6–8 while A and D go on to 16–18: two ways on, no join.
        var trips = List.of(
                fixtures.trip("A", 360, 1, 2, 3, 4, 5, 16, 17, 18),
                fixtures.trip("B", 365, 1, 2, 3, 4, 5, 6, 7, 8),
                fixtures.trip("C", 370, 4, 5, 6, 7, 8),
                fixtures.trip("D", 375, 4, 5, 16, 17, 18));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).hasSize(3);
    }

    @Test
    void anOutAndBackRouteIsTwoDirectionsNotOneLongCorridor() {
        // A and B go out on 1–5, turn round and come back on the opposite platforms 104–101 in the same trip.
        var trips = List.of(
                fixtures.trip("A", 400, 1, 2, 3, 4, 5, 104, 103, 102, 101),
                fixtures.trip("B", 410, 1, 2, 3, 4, 5, 104, 103, 102, 101));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).singleElement().satisfies(c -> {
            assertThat(c.stops()).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(5);
            assertThat(c.oneWay()).isFalse();
        });
    }

    @Test
    void bothDirectionsMakeOneRowWhenTheLinesRunBackTheSameWay() {
        var trips = new ArrayList<TripStops>();
        // Forward on stops 1–5; back on the opposite platforms 105–101.
        for (int i = 0; i < 4; i++) {
            trips.add(fixtures.trip("A", 390 + i * 30, 1, 2, 3, 4, 5));
            trips.add(fixtures.trip("B", 405 + i * 30, 1, 2, 3, 4, 5));
            trips.add(fixtures.trip("A", 400 + i * 30, 105, 104, 103, 102, 101));
        }
        trips.add(fixtures.trip("B", 420, 105, 104, 103, 102, 101));
        var corridors = CorridorService.corridors(trips, CorridorFixtures.stops());

        assertThat(corridors).singleElement().satisfies(c -> {
            assertThat(c.oneWay()).isFalse();
            assertThat(c.buses()).isEqualTo(13);
            // The busier direction is shown.
            assertThat(c.from()).isEqualTo("S1");
            // Peak 06:30–08:30 and 15:00–17:00 = 240 min: forward 8 departures (30 min), back 5 (48 min).
            assertThat(c.peakIntervalMin()).isEqualTo(39.0);
            // Forward every 15 min exactly: CV 0. Back 06:40, 07:00, 07:10, 07:40, 08:10: gaps 20, 10, 30, 30 →
            // CV 0.37. The directions are averaged: 0.185 → 0.19.
            assertThat(c.peakCv()).isEqualTo(0.19);
            assertThat(c.offPeakIntervalMin()).isNull();
        });
    }

    @Test
    void endsLessThan500MetresApartPairDirections() {
        var stops = CorridorFixtures.stops();
        // The way back ends at "S1 bis", 40 m from S1.
        stops.put(99L, new StopRef("S1 bis", 53.0 + 0.009, 23.0006));
        var trips = List.of(
                fixtures.trip("A", 400, 1, 2, 3, 4), fixtures.trip("B", 410, 1, 2, 3, 4),
                fixtures.trip("A", 420, 104, 103, 102, 99), fixtures.trip("B", 430, 104, 103, 102, 99));
        assertThat(CorridorService.corridors(trips, stops)).singleElement()
                .satisfies(c -> assertThat(c.oneWay()).isFalse());
    }

    @Test
    void aWayBackThatStartsAStopFurtherStillPairs() {
        // Forward 1–5; back 106–102 (opposite platforms of 6 to 2): four of five names in common.
        var trips = List.of(
                fixtures.trip("A", 400, 1, 2, 3, 4, 5), fixtures.trip("B", 410, 1, 2, 3, 4, 5),
                fixtures.trip("A", 420, 106, 105, 104, 103, 102), fixtures.trip("B", 430, 106, 105, 104, 103, 102));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops())).singleElement()
                .satisfies(c -> assertThat(c.oneWay()).isFalse());
    }

    @Test
    void twoStretchesTheSameWayNeverPair() {
        // 1–4 and 3–6 overlap, run the same way, and have the same lines: they are not each other's way back.
        var trips = List.of(
                fixtures.trip("A", 400, 1, 2, 3, 4), fixtures.trip("B", 410, 1, 2, 3, 4),
                fixtures.trip("C", 420, 1, 2, 3, 4),
                fixtures.trip("A", 430, 3, 4, 5, 6), fixtures.trip("B", 440, 3, 4, 5, 6));
        assertThat(CorridorService.corridors(trips, CorridorFixtures.stops()))
                .allSatisfy(c -> assertThat(c.oneWay()).isTrue());
    }
}
