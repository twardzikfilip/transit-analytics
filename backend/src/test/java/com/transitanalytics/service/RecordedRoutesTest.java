package com.transitanalytics.service;

import com.transitanalytics.dto.LineDetailDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecordedRoutesTest {

    private static final Instant T = Instant.parse("2026-11-17T07:00:00Z");

    /**
     * A bus's run from (lat0, lon) north by {@code km} km in reports every 20 s (about 340 m apart, 60 km/h), from
     * {@code start} seconds after T; heading south when {@code km} is negative.
     */
    private static List<RecordedRoutes.Report> run(long vehicle, int start, String headsign, double lat0, double lon,
                                                   double km, String from, String to) {
        List<RecordedRoutes.Report> reports = new ArrayList<>();
        int n = (int) Math.round(Math.abs(km) / 0.34);
        double step = Math.signum(km) * 0.34 / 111.32;
        for (int i = 0; i <= n; i++) {
            reports.add(new RecordedRoutes.Report(vehicle, T.plusSeconds(start + 20L * i), lat0 + i * step, lon,
                    headsign, i == 0 ? from : i == n ? to : "Middle"));
        }
        return reports;
    }

    @Test
    void groupsRunsByDestinationAndStreetsTheMostDrivenFirst() {
        List<RecordedRoutes.Report> reports = new ArrayList<>();
        // Three full runs north to Wasilków along 23.10 E, one starting part-way (joins them).
        reports.addAll(run(1, 0, "Wasilków", 53.10, 23.10, 10, "Dworzec", "Wasilków/Pętla"));
        reports.addAll(run(2, 0, "Wasilków", 53.10, 23.1001, 10, "Dworzec", "Wasilków/Pętla"));
        reports.addAll(run(3, 0, "Wasilków", 53.10, 23.0999, 10, "Dworzec", "Wasilków/Pętla"));
        reports.addAll(run(4, 0, "Wasilków", 53.14, 23.10, 5, "Midway", "Wasilków/Pętla"));
        // Twice the other way, then once north along other streets (2 km east): a variant of its own.
        reports.addAll(run(1, 3600, "Dworzec", 53.19, 23.10, -10, "Wasilków/Pętla", "Dworzec"));
        reports.addAll(run(2, 3600, "Dworzec", 53.19, 23.10, -10, "Wasilków/Pętla", "Dworzec"));
        reports.addAll(run(5, 0, "Wasilków", 53.10, 23.13, 9, "Dworzec", "Wasilków/Pętla"));
        // Noise: a bus standing, a short hop, a run without a destination.
        reports.add(new RecordedRoutes.Report(6, T, 53.1, 23.2, "Wasilków", "x"));
        reports.addAll(run(7, 0, "Wasilków", 53.12, 23.20, 0.7, "a", "b"));
        reports.addAll(run(8, 0, null, 53.12, 23.20, 5, "a", "b"));

        LineDetailDto.Recorded recorded = RecordedRoutes.recorded(reports);

        assertThat(recorded.runs()).isEqualTo(7);
        assertThat(recorded.first()).isEqualTo(T);
        assertThat(recorded.variants()).extracting(LineDetailDto.RecordedVariant::runs).containsExactly(4, 2, 1);
        LineDetailDto.RecordedVariant main = recorded.variants().get(0);
        assertThat(main.id()).isEqualTo(1);
        assertThat(main.headsign()).isEqualTo("Wasilków");
        assertThat(main.from()).isEqualTo("Dworzec");
        assertThat(main.to()).isEqualTo("Wasilków/Pętla");
        assertThat(main.km()).isCloseTo(10.0, within(0.3));
        // A straight run simplifies to its two ends.
        assertThat(main.path()).hasSize(2);
        assertThat(recorded.variants().get(1).headsign()).isEqualTo("Dworzec");
        assertThat(recorded.variants().get(1).lastSeen()).isAfter(T.plusSeconds(3600));
        assertThat(recorded.variants().get(2).path().get(0)[1]).isEqualTo(23.13);
    }

    @Test
    void anUnusuallyLongRunDoesNotBecomeThePathTheOthersFollow() {
        List<RecordedRoutes.Report> reports = new ArrayList<>();
        reports.addAll(run(1, 0, "Wasilków", 53.10, 23.10, 10, "Dworzec", "Wasilków/Pętla"));
        reports.addAll(run(2, 0, "Wasilków", 53.10, 23.10, 10, "Dworzec", "Wasilków/Pętla"));
        // Once on to the depot, 10 km further: 2× the usual length.
        reports.addAll(run(3, 0, "Wasilków", 53.10, 23.10, 20, "Dworzec", "Zajezdnia"));

        LineDetailDto.Recorded recorded = RecordedRoutes.recorded(reports);

        assertThat(recorded.variants()).extracting(LineDetailDto.RecordedVariant::runs).containsExactly(2, 1);
        assertThat(recorded.variants().get(0).km()).isCloseTo(10.0, within(0.3));
        assertThat(recorded.variants().get(1).to()).isEqualTo("Zajezdnia");
    }

    @Test
    void handlesNothingUsableAndTinyPaths() {
        assertThat(RecordedRoutes.recorded(List.of()).variants()).isEmpty();
        assertThat(RecordedRoutes.recorded(List.of()).first()).isNull();
        List<double[]> two = List.of(new double[] {53.1, 23.1}, new double[] {53.2, 23.1});
        assertThat(RecordedRoutes.simplify(two, 10)).isSameAs(two);
        assertThat(RecordedRoutes.distance(new double[] {53.1, 23.1}, List.of(new double[] {53.1, 23.1})))
                .isZero();
        // A wiggle of 50 m is kept at 10 m tolerance.
        List<double[]> bent = List.of(new double[] {53.10, 23.10}, new double[] {53.11, 23.1007},
                new double[] {53.12, 23.10});
        assertThat(RecordedRoutes.simplify(bent, 10)).hasSize(3);
        assertThat(RecordedRoutes.simplify(bent, 100)).hasSize(2);
    }
}
