package com.transitanalytics.service;

import com.transitanalytics.dto.NetworkCoverageDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The built-in city, districts, woods and residents, with stops placed here. */
class NetworkCoverageServiceTest {

    private final NetworkCoverageService service = new NetworkCoverageService(null, JsonMapper.builder().build());

    @Test
    void measuresTheCityItsDistrictsAndWoods() {
        NetworkCoverageDto none = service.compute(List.of());
        // Białystok is 102.1 km² (GUS); OpenStreetMap's border measures within a percent of it.
        assertThat(none.city().areaKm2()).isCloseTo(102.1, within(1.0));
        assertThat(none.coverage().coveredKm2()).isZero();
        assertThat(none.districts()).hasSize(29);
        assertThat(none.districts().stream().mapToInt(NetworkCoverageDto.District::population).sum())
                .as("permanent and temporary residents").isEqualTo(259_778 + 5_741);
        assertThat(none.officialPopulation()).isEqualTo(289_217);
        assertThat(none.districts().stream().mapToDouble(NetworkCoverageDto.District::areaKm2).sum())
                .isCloseTo(none.city().areaKm2(), within(0.5));
        assertThat(none.forestKm2()).isPositive();
        assertThat(none.withoutForest().areaKm2()).isCloseTo(none.city().areaKm2() - none.forestKm2(), within(0.01));
        assertThat(none.forests()).extracting(NetworkCoverageDto.Forest::name).contains("Las Zwierzyniecki");
        assertThat(none.forests().stream().mapToDouble(NetworkCoverageDto.Forest::areaKm2).sum())
                .isCloseTo(none.forestKm2(), within(0.05));

        NetworkCoverageDto.District dojlidy = none.districts().stream()
                .filter(d -> d.name().equals("Dojlidy")).findFirst().orElseThrow();
        assertThat(dojlidy.largeForest()).isTrue();
        assertThat(dojlidy.landDensity()).isGreaterThan(dojlidy.density());
        assertThat(dojlidy.landKm2()).isCloseTo(dojlidy.areaKm2() - dojlidy.forestKm2(), within(0.002));
    }

    @Test
    void measuresTheVillagesAndTownsAroundTheCity() {
        NetworkCoverageDto.Belt belt = service.compute(List.of()).belt();
        assertThat(belt.places()).hasSizeGreaterThan(80);
        // OpenStreetMap's borders against the areas the gmina gives (Gminny Program Rewitalizacji, table 1).
        assertThat(belt.places()).filteredOn(p -> p.name().equals("Kleosin")).singleElement().satisfies(k -> {
            assertThat(k.areaKm2()).isCloseTo(1.09, within(0.03));
            assertThat(k.population()).isEqualTo(4564);
            assertThat(k.populationDate()).isEqualTo("2023-12-31");
            assertThat(k.density()).isCloseTo(4564 / k.areaKm2(), within(1.0));
        });
        assertThat(belt.places()).filteredOn(p -> p.name().equals("Wasilków")).singleElement()
                .satisfies(w -> assertThat(w.kind()).isEqualTo("town"));
        assertThat(belt.places()).filteredOn(p -> p.population() == null).allSatisfy(p -> {
            assertThat(p.density()).isNull();
            assertThat(p.landDensity()).isNull();
        });
        assertThat(belt.woods()).isNotEmpty();
    }

    @Test
    void aStopInTheCentreCoversADiscOf400Metres() {
        // Rynek Kościuszki.
        NetworkCoverageDto one = service.compute(List.of(new double[] {53.1325, 23.1590}));
        assertThat(one.coverage().coveredKm2()).isCloseTo(Math.PI * 0.4 * 0.4, within(0.005));
        assertThat(one.coverage().share()).isCloseTo(one.coverage().coveredKm2() / one.city().areaKm2(), within(1e-3));
        assertThat(one.districts()).filteredOn(d -> d.name().equals("Centrum")).singleElement()
                .satisfies(d -> assertThat(d.coveredShare()).isGreaterThan(0.2));
    }
}
