package com.transitanalytics.routes;

import com.transitanalytics.dto.RouteDetailDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleUsageTest {

    @Test
    void vehicleUseCountsDaysAndFindsBusChangesWithinABrigadeDay() {
        java.time.LocalDate d1 = java.time.LocalDate.of(2026, 11, 9);
        java.time.LocalDate d2 = java.time.LocalDate.of(2026, 11, 10);
        java.time.Instant t = java.time.Instant.parse("2026-11-09T06:00:00Z");
        var rows = List.of(
                new VehicleUsage.VehicleDay(d1, "103-01", "224", "Solaris", t, t.plusSeconds(36000), 600),
                new VehicleUsage.VehicleDay(d1, "103-01", "221", "Solaris", t.plusSeconds(36400),
                        t.plusSeconds(44000), 140),
                new VehicleUsage.VehicleDay(d2, "103-01", "224", "Solaris", t, t.plusSeconds(3600), 60),
                new VehicleUsage.VehicleDay(d2, "103-02", "300", "MAN", t, t.plusSeconds(3600), 60),
                new VehicleUsage.VehicleDay(d2, "103-02", "301", "MAN", t, t.plusSeconds(120), 2),  // a glitch
                new VehicleUsage.VehicleDay(d2, "", "999", null, t, t.plusSeconds(600), 10));
        var use = VehicleUsage.vehicleUse(rows);

        assertThat(use.days()).isEqualTo(2);
        assertThat(use.vehicles()).extracting(RouteDetailDto.VehicleUse::number)
                .containsExactly("224", "221", "300", "999");
        assertThat(use.vehicles().get(0).days()).isEqualTo(2);
        assertThat(use.vehicles().get(3).brigades()).isEmpty();
        assertThat(use.brigades()).containsExactly(
                new RouteDetailDto.BrigadeVehicles("103-01", "224", 2, 2, 2),
                new RouteDetailDto.BrigadeVehicles("103-02", "300", 1, 1, 1));
        assertThat(use.switches()).hasSize(1);
        assertThat(use.switches().get(0).spells()).extracting(RouteDetailDto.VehicleSpell::number)
                .containsExactly("224", "221");
    }
}
