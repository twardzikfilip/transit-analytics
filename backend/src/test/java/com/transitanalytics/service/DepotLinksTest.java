package com.transitanalytics.service;

import com.transitanalytics.classification.ClassificationConfigs;
import com.transitanalytics.service.LineTimetable.TripRow;
import com.transitanalytics.service.StopPlaces.StopRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DepotLinksTest {

    private static final StopRef A = new StopRef("A", 53.12, 23.13);
    private static final StopRef B = new StopRef("B", 53.13, 23.18);
    private static final StopRef UNKNOWN = new StopRef("Nowhere", null, null);

    private static TripRow trip(String duty, int start, StopRef from, StopRef to) {
        return new TripRow(start, duty, 0, "s", "H", start * 60, start * 60 + 30 * 60, 10.0, 10, from.name(),
                to.name(), from, to);
    }

    @Test
    void findsWhereBrigadesStartAndEndTheirDay() {
        List<TripRow> trips = List.of(
                // Brigade 1: out at A, in at B; brigade 2: out at A, in at A; brigade 3 ends somewhere unknown.
                trip("1", 360, A, B), trip("1", 400, B, A), trip("1", 440, A, B),
                trip("2", 370, A, B), trip("2", 410, B, A),
                trip("3", 380, B, UNKNOWN));
        assertThat(LineDetailService.entries(trips))
                .extracting(e -> e.kind() + " " + e.stop().name() + " " + e.brigades())
                .containsExactly("pull_out A 2", "pull_out B 1", "pull_in A 1", "pull_in B 1");
    }

    @Test
    void readsTheDepotsWithTheirOutlinesAndFindsTheNearest() {
        MapProperties map = ClassificationConfigs.bind("map/depots.yml", "app.map", MapProperties.class);
        assertThat(map.depots()).extracting(MapProperties.Depot::code).containsExactly("KPKM", "KPK", "KZK");
        assertThat(map.depot("KPK").outlines()).hasSize(2);
        assertThat(map.depot("XYZ")).isNull();
        assertThat(map.routing().enabled()).isTrue();
        double[] kpkm = map.depot("KPKM").centre();
        assertThat(kpkm[0]).isCloseTo(53.1167, within(0.001));
        assertThat(kpkm[1]).isCloseTo(23.1297, within(0.001));
        // From the east end of Branickiego: KZK's; from Transportowa: one of those on Składowa.
        assertThat(LineDetailService.nearest(new double[] {53.135, 23.19}, map.depots()).code()).isEqualTo("KZK");
        assertThat(LineDetailService.nearest(new double[] {53.109, 23.115}, map.depots()).code()).isIn("KPKM", "KPK");
    }

    @Test
    void knowsWhichCompanyRunsEachLine() {
        var operators = ClassificationConfigs.operators();
        assertThat(operators.companies()).extracting(c -> c.code()).containsExactly("KPKM", "KPK", "KZK");
        assertThat(operators.companyOf("103").code()).isEqualTo("KZK");
        assertThat(operators.companyOf("16").code()).isEqualTo("KPK");
        assertThat(operators.companyOf("N1").code()).isEqualTo("KPKM");
        assertThat(operators.companyOf("400")).isNull();
        assertThat(operators.company("KZK").depot()).isEqualTo("KZK");
        assertThat(operators.organiser()).startsWith("BKM");
        // Every line once.
        List<String> all = operators.companies().stream().flatMap(c -> c.lines().stream()).toList();
        assertThat(all).doesNotHaveDuplicates().hasSize(62);
    }
}
