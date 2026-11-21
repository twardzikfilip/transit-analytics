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
        assertThat(NONE.coverages().get(0).withoutForest().areaKm2())
                .isCloseTo(NONE.city().areaKm2() - NONE.forestKm2(), within(0.01));
        assertThat(NONE.forests()).extracting(NetworkCoverageDto.Forest::name).contains("Las Zwierzyniecki");
        assertThat(NONE.forests().stream().mapToDouble(NetworkCoverageDto.Forest::areaKm2).sum())
                .isCloseTo(NONE.forestKm2(), within(0.05));

        PlaceDensity dojlidy = place(NONE.districts(), "Dojlidy");
        assertThat(dojlidy.kind()).isEqualTo("district");
        assertThat(dojlidy.largeForest()).isTrue();
        assertThat(dojlidy.landDensity()).isGreaterThan(dojlidy.density());
        assertThat(dojlidy.landKm2()).isCloseTo(dojlidy.areaKm2() - dojlidy.forestKm2(), within(0.002));
        assertThat(dojlidy.populationSource()).contains("2025");
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
