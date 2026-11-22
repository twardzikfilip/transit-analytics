package com.transitanalytics.service;

import com.transitanalytics.domain.VehicleClass;
import com.transitanalytics.dto.FleetDto;
import com.transitanalytics.dto.FleetDto.Usage;
import com.transitanalytics.dto.FleetDto.Vehicle;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.FleetService.Figures;
import com.transitanalytics.service.FleetService.Report;
import com.transitanalytics.service.FleetService.Segment;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FleetServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final Vehicle A = new Vehicle(1, "230", "Solaris Urbino 12", "BKM", VehicleClass.MAXI);
    private static final Vehicle B = new Vehicle(2, "9", "Solaris Urbino 12", "BKM", VehicleClass.MAXI);
    private static final Vehicle C = new Vehicle(3, "1050", null, "TUROSN", null);
    private static final Vehicle IDLE = new Vehicle(4, "17", "Yutong U12", "BKM", VehicleClass.MAXI);

    private static Instant at(String hhmm) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }

    private static int seconds(String hhmm) {
        return (int) (at(hhmm).getEpochSecond() - ServiceDay.gtfsTimeZero(DAY).getEpochSecond());
    }

    @Test
    void ordersSizeClassesSmallestFirstAndTakesTheMakeFromTheModelName() {
        Vehicle big = new Vehicle(10, "500", "Solaris Urbino 18", "BKM", VehicleClass.MEGA);
        Vehicle mini = new Vehicle(11, "501", "Karsan Jest", "BKM", VehicleClass.MINI);
        Vehicle dd = new Vehicle(12, "502", "MAN ND313 Lion`s City DD", "X", VehicleClass.DOUBLE_DECKER);
        assertThat(FleetService.classes(List.of(dd, big, A, mini), List.of())).extracting(FleetDto.ModelCount::model)
                .containsExactly("mini", "maxi", "mega", "double_decker");
        assertThat(FleetService.make("  Mercedes-Benz Conecto G")).isEqualTo("Mercedes-Benz");
        assertThat(FleetService.make(" ")).isEqualTo("unknown");
        assertThat(FleetService.make(null)).isEqualTo("unknown");
    }

    @Test
    void sumsUsePerVehicleWithDaysAndLinesInLineOrder() {
        List<Usage> usage = FleetService.usage(List.of(
                new Segment(A, DAY, "103", 3600), new Segment(A, DAY, "11", 1800),
                new Segment(A, DAY.plusDays(1), "103", 3600), new Segment(B, DAY, "9", 600)));

        assertThat(usage).extracting(u -> u.vehicle().number()).containsExactly("230", "9");
        assertThat(usage.get(0).hours()).isEqualTo(2.5);
        assertThat(usage.get(0).days()).isEqualTo(2);
        assertThat(usage.get(0).lines()).containsExactly("11", "103");
        assertThat(usage.get(1).hours()).isEqualTo(0.2);
    }

    @Test
    void reportsTheChosenOperatorsBusesWithIdleOnesModelsAndTops() {
        List<Usage> used = List.of(new Usage(A, 10, 1, List.of("103")), new Usage(B, 2, 1, List.of("9")),
                new Usage(C, 5, 1, List.of("400")));
        var breakdown = new FleetDto.Breakdown(B, at("07:00"), "9", "009-01", "North", "D", null, 1.5);
        var figures = new Figures(at("00:00"), at("23:00"), List.of(A, B, C, IDLE), used, List.of(breakdown));

        FleetDto.Report all = figures.report(Set.of());
        assertThat(all.inUse()).isEqualTo(3);
        assertThat(all.fleet()).isEqualTo(4);
        assertThat(all.usage()).extracting(u -> u.vehicle().number() + " " + u.hours())
                .containsExactly("230 10.0", "1050 5.0", "9 2.0", "17 0.0");
        assertThat(all.mostUsed()).extracting(u -> u.vehicle().number()).containsExactly("230", "1050", "9");
        assertThat(all.leastUsed()).extracting(u -> u.vehicle().number()).containsExactly("9", "1050", "230");
        assertThat(all.models()).extracting(m -> m.model() + " " + m.inUse() + "/" + m.fleet())
                .containsExactly("Solaris Urbino 12 2/2", "unknown 1/1", "Yutong U12 0/1");
        assertThat(all.agencies()).extracting(a -> a.name() + " " + a.vehicles()).containsExactly("BKM 3", "TUROSN 1");
        // By size class, smallest first and unknown last; by make, the model name's first word.
        assertThat(all.classes()).extracting(m -> m.model() + " " + m.inUse() + "/" + m.fleet())
                .containsExactly("maxi 2/3", "unknown 1/1");
        assertThat(all.makes()).extracting(m -> m.model() + " " + m.inUse() + "/" + m.fleet())
                .containsExactly("Solaris 2/2", "unknown 1/1", "Yutong 0/1");
        assertThat(all.breakdowns()).containsExactly(breakdown);

        FleetDto.Report turosn = figures.report(Set.of("TUROSN"));
        assertThat(turosn.inUse()).isEqualTo(1);
        assertThat(turosn.fleet()).isEqualTo(1);
        assertThat(turosn.breakdowns()).isEmpty();
        assertThat(turosn.agencies()).as("every operator stays to choose from").hasSize(2);
    }

    @Test
    void splitsABusDayIntoRunsAndBreaks() {
        List<Report> reports = new ArrayList<>();
        // 06:00–06:02 towards Rynek, then towards Dworzec 06:03–06:04 (a new run: another headsign).
        reports.add(new Report(at("06:00"), "103", "103-05", "Rynek"));
        reports.add(new Report(at("06:01"), "103", "103-05", "Rynek"));
        reports.add(new Report(at("06:02"), "103", "103-05", "Rynek"));
        reports.add(new Report(at("06:03"), "103", "103-05", "Dworzec"));
        reports.add(new Report(at("06:04"), "103", "103-05", "Dworzec"));
        // Out of service (no line) at 06:05, then back at 07:00 on line 11 after a break.
        reports.add(new Report(at("06:05"), "", null, null));
        reports.add(new Report(at("07:00"), "11", "011-02", "Wysoki Stoczek"));
        // A gap of 10 minutes in reports splits the run even on the same line.
        reports.add(new Report(at("07:10"), "11", "011-02", "Wysoki Stoczek"));

        List<FleetDto.Day> days = FleetService.days(reports);

        assertThat(days).singleElement().satisfies(d -> {
            assertThat(d.day()).isEqualTo(DAY);
            assertThat(d.start()).isEqualTo(ServiceDay.gtfsTimeZero(DAY));
            assertThat(d.runs()).containsExactly(
                    new FleetDto.Run(seconds("06:00"), seconds("06:02"), "103", "103-05", "Rynek"),
                    new FleetDto.Run(seconds("06:03"), seconds("06:04"), "103", "103-05", "Dworzec"),
                    new FleetDto.Run(seconds("07:00"), seconds("07:00"), "11", "011-02", "Wysoki Stoczek"),
                    new FleetDto.Run(seconds("07:10"), seconds("07:10"), "11", "011-02", "Wysoki Stoczek"));
            assertThat(d.breaks()).containsExactly(new FleetDto.Break(seconds("06:04"), seconds("07:00")));
            // Seven reports in service, a minute each at most.
            assertThat(d.hours()).isEqualTo(0.1);
        });
        assertThat(FleetService.days(List.of(new Report(at("06:00"), null, null, null))))
                .as("a day without service").isEmpty();
    }

    private static Outcome outcome(Kind kind, String fate, List<Long> vehicles, String departure) {
        var trip = new TripAnomalyDto.Trip(kind.key,
                List.of(TripAnomalyDto.Phrase.of("betweenRuns")), "t" + departure, "9", "009-01", "North",
                "A", "E", at(departure), at(departure).plusSeconds(600), 5, List.of(), null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), List.of(), fate, null, null, null, null, null, "D", null, null,
                at(departure).plusSeconds(240), null, null, null, null, List.of(), List.of());
        return new Outcome("9", at(departure), at(departure).plusSeconds(600), kind, trip, vehicles);
    }

    @Test
    void findsTheBreakdownsAndMissedTripsABusWasInvolvedIn() {
        Outcome brokeDown = outcome(Kind.VEHICLE_CHANGED, "broken-down", List.of(1L, 2L), "07:00");
        Outcome reassigned = outcome(Kind.VEHICLE_CHANGED, "reassigned", List.of(1L, 2L), "08:00");
        Outcome tookOver = outcome(Kind.VEHICLE_CHANGED, "broken-down", List.of(2L, 1L), "09:00");
        Outcome missed = outcome(Kind.MISSED, null, List.of(1L), "10:00");
        Outcome lost = outcome(Kind.LOST, null, List.of(1L), "11:00");
        Outcome completed = new Outcome("9", at("12:00"), at("12:10"), Kind.COMPLETED, null, List.of());

        assertThat(FleetService.brokeDown(brokeDown)).isTrue();
        assertThat(FleetService.brokeDown(reassigned)).isFalse();
        assertThat(FleetService.brokeDown(completed)).isFalse();
        assertThat(FleetService.incidents(1L, List.of(brokeDown, reassigned, tookOver, missed, lost, completed)))
                .extracting(i -> i.kind() + " " + i.scheduledDeparture())
                .containsExactly("missed " + at("10:00"), "vehicle-changed " + at("07:00"));
    }

    @Test
    void countsDaysToOneDecimal() {
        assertThat(FleetService.daysBetween(at("06:00"), at("06:00").plusSeconds(36 * 3600))).isEqualTo(1.5);
        assertThat(FleetService.daysBetween(at("06:00"), at("07:00"))).isEqualTo(0.0);
    }
}
