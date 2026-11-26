package com.transitanalytics.service;

import com.transitanalytics.dto.NetworkCoverageDto;
import com.transitanalytics.dto.NetworkCoverageDto.PlaceDensity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The built-in places (places/), with stops placed here. */
class NetworkCoverageServiceTest {

    private static final NetworkCoverageService SERVICE =
            new NetworkCoverageService(null, NetworkCoverageProperties.defaults());
    private static final NetworkCoverageDto NONE = SERVICE.compute(List.of());

    private static PlaceDensity place(List<PlaceDensity> places, String name) {
        return places.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void measuresTheCityItsDistrictsAndWoods() {
        // Białystok is 102.1 km² (GUS); OpenStreetMap's border measures within a percent of it.
        assertThat(NONE.city().areaKm2()).isCloseTo(102.1, within(1.0));
        assertThat(NONE.coverages()).extracting(NetworkCoverageDto.RadiusCoverage::radiusMeters)
                .containsExactly(400, 500, 600);
        assertThat(NONE.coverages()).allSatisfy(c -> assertThat(c.city().coveredKm2()).isZero());
        assertThat(NONE.districts()).hasSize(29);
        assertThat(NONE.districts().stream().mapToInt(PlaceDensity::population).sum())
                .as("permanent and temporary residents").isEqualTo(259_778 + 5_741);
        assertThat(NONE.official().population()).isEqualTo(289_217);
        assertThat(NONE.districts().stream().mapToDouble(PlaceDensity::areaKm2).sum())
                .isCloseTo(NONE.city().areaKm2(), within(0.5));
        assertThat(NONE.forestKm2()).isPositive();
        assertThat(NONE.coverages().get(0).withoutWoodsAndValley().areaKm2())
                .isCloseTo(NONE.city().areaKm2() - NONE.forestKm2() - NONE.valleyKm2(), within(0.01));
        assertThat(NONE.forests()).extracting(NetworkCoverageDto.Forest::name).contains("Las Zwierzyniecki");
        assertThat(NONE.forests().stream().mapToDouble(NetworkCoverageDto.Forest::areaKm2).sum())
                .isCloseTo(NONE.forestKm2(), within(0.05));

        PlaceDensity dojlidy = place(NONE.districts(), "Dojlidy");
        assertThat(dojlidy.kind()).isEqualTo("district");
        assertThat(dojlidy.largeForest()).isTrue();
        assertThat(dojlidy.landDensity()).isGreaterThan(dojlidy.density());
        assertThat(dojlidy.landKm2())
                .isCloseTo(dojlidy.areaKm2() - dojlidy.forestKm2() - dojlidy.valleyKm2(), within(0.003));
        assertThat(dojlidy.populationSource()).contains("2025");
    }

    @Test
    void leavesTheBialaValleyOutLikeTheWoods() {
        // Two stretches: from Piastowska to Stawy Dojlidzkie, and from Białostoczek west and north.
        assertThat(NONE.valley()).hasSize(2);
        assertThat(NONE.valley()).allSatisfy(v -> assertThat(v.areaKm2()).isBetween(0.5, 6.0));
        assertThat(NONE.valleyKm2()).isCloseTo(
                NONE.valley().stream().mapToDouble(NetworkCoverageDto.ValleyStretch::areaKm2).sum(), within(0.002));
        // The ponds are in Dojlidy, the meadows by Bacieczki in Wysoki Stoczek; the centre has none.
        assertThat(place(NONE.districts(), "Dojlidy").valleyKm2()).isGreaterThan(0.3);
        assertThat(place(NONE.districts(), "Wysoki Stoczek").valleyKm2()).isGreaterThan(0.3);
        assertThat(place(NONE.districts(), "Centrum").valleyKm2()).isZero();
        assertThat(NONE.districts().stream().mapToDouble(PlaceDensity::valleyKm2).sum())
                .isCloseTo(NONE.valleyKm2(), within(0.05));
        assertThat(NONE.belt().places()).allSatisfy(p -> assertThat(p.valleyKm2()).isZero());
    }

    @Test
    void measuresTheVillagesAndTownsAroundTheCity() {
        NetworkCoverageDto.Belt belt = NONE.belt();
        assertThat(belt.places()).hasSizeGreaterThan(80);
        // OpenStreetMap's borders against the areas the gmina gives (Gminny Program Rewitalizacji, table 1).
        PlaceDensity kleosin = place(belt.places(), "Kleosin");
        assertThat(kleosin.areaKm2()).isCloseTo(1.09, within(0.03));
        assertThat(kleosin.population()).isEqualTo(4564);
        assertThat(kleosin.populationDate()).isEqualTo("2023-12-31");
        assertThat(kleosin.density()).isCloseTo(4564 / kleosin.areaKm2(), within(1.0));
        assertThat(place(belt.places(), "Wasilków").kind()).isEqualTo("town");
        assertThat(belt.places()).filteredOn(p -> p.population() == null).isNotEmpty().allSatisfy(p -> {
            assertThat(p.density()).isNull();
            assertThat(p.landDensity()).isNull();
        });
        assertThat(belt.woods()).isNotEmpty();
        assertThat(belt.open()).isNotEmpty();
        assertThat(NONE.districts()).allSatisfy(d -> assertThat(d.openKm2()).isZero());
        // The arc from Supraśl to Baciuty reaches further.
        for (String name : List.of("Supraśl", "Zabłudów", "Juchnowiec Kościelny", "Juchnowiec Dolny", "Sobolewo",
                "Turośń Kościelna", "Turośń Dolna", "Baciuty")) {
            PlaceDensity p = place(belt.places(), name);
            assertThat(p.openKm2()).as(name).isPositive();
            assertThat(p.landKm2()).as(name).isPositive()
                    .isCloseTo(p.areaKm2() - p.forestKm2() - p.openKm2(), within(0.003));
            assertThat(p.landDensity()).as(name).isGreaterThan(p.density());
        }
        // The census' Solniki, not Solniczki.
        assertThat(place(belt.places(), "Solniki").population()).isEqualTo(52);
    }

    @Test
    void aStopInTheCentreCoversADiscAtEachDistance() {
        // Rynek Kościuszki.
        NetworkCoverageDto one = SERVICE.compute(List.<double[]>of(new double[] {53.1325, 23.1590}));
        for (NetworkCoverageDto.RadiusCoverage c : one.coverages()) {
            double r = c.radiusMeters() / 1000.0;
            assertThat(c.city().coveredKm2()).as("%d m", c.radiusMeters()).isCloseTo(Math.PI * r * r, within(0.01));
            assertThat(c.city().share()).isCloseTo(c.city().coveredKm2() / one.city().areaKm2(), within(1e-3));
        }
        List<Double> centrum = place(one.districts(), "Centrum").coveredShares();
        assertThat(centrum).hasSize(3);
        assertThat(centrum.get(0)).isGreaterThan(0.2);
        assertThat(centrum.get(1)).isGreaterThan(centrum.get(0));
        assertThat(centrum.get(2)).isGreaterThan(centrum.get(1));
    }
}
