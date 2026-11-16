package com.transitanalytics.service;

import com.transitanalytics.dto.LineDetailDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.transitanalytics.service.LineTimetable.*;
import static com.transitanalytics.service.Periods.*;

/** Which buses served a line: days per bus, the most used bus per brigade, and buses changing within a day. */
final class VehicleUsage {

    // --- pure helpers (unit tested) ------------------------------------------------------------------------------

    /** One bus on one brigade (or none: "") on one service day. */
    record VehicleDay(LocalDate day, String brigade, String number, String model, Instant from, Instant to,
                      int reports) {
    }

    /** A bus seen for fewer reports than this (about one a minute) on a brigade and day is left out as a glitch. */
    static final int MIN_REPORTS = 5;

    /**
     * Which buses served the line: per bus, the days and brigades; per brigade, its most used bus; and the brigades
     * that changed bus during a day, in order.
     */
    static LineDetailDto.Vehicles vehicleUse(List<VehicleDay> rows) {
        List<VehicleDay> kept = rows.stream().filter(r -> r.reports() >= MIN_REPORTS).toList();

        Map<String, List<VehicleDay>> byVehicle = kept.stream()
                .collect(Collectors.groupingBy(VehicleDay::number, TreeMap::new, Collectors.toList()));
        List<LineDetailDto.VehicleUse> vehicles = byVehicle.entrySet().stream()
                .map(e -> new LineDetailDto.VehicleUse(e.getKey(),
                        e.getValue().stream().map(VehicleDay::model).filter(Objects::nonNull).findFirst().orElse(null),
                        (int) e.getValue().stream().map(VehicleDay::day).distinct().count(),
                        e.getValue().stream().map(VehicleDay::brigade).filter(b -> !b.isEmpty()).distinct().sorted()
                                .toList(),
                        e.getValue().stream().map(VehicleDay::to).max(Comparator.naturalOrder()).orElseThrow()))
                .sorted(Comparator.comparingInt(LineDetailDto.VehicleUse::days).reversed()
                        .thenComparing(LineDetailDto.VehicleUse::number))
                .toList();

        Map<String, List<VehicleDay>> byBrigade = kept.stream().filter(r -> !r.brigade().isEmpty())
                .collect(Collectors.groupingBy(VehicleDay::brigade, TreeMap::new, Collectors.toList()));
        List<LineDetailDto.BrigadeVehicles> brigades = byBrigade.entrySet().stream().map(e -> {
            Map<String, Long> days = e.getValue().stream().collect(Collectors.groupingBy(VehicleDay::number,
                    TreeMap::new, Collectors.mapping(VehicleDay::day, Collectors.collectingAndThen(
                            Collectors.toSet(), set -> (long) set.size()))));
            var most = days.entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue()
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .orElseThrow();
            return new LineDetailDto.BrigadeVehicles(e.getKey(), most.getKey(), most.getValue().intValue(),
                    (int) e.getValue().stream().map(VehicleDay::day).distinct().count(), days.size());
        }).toList();

        List<LineDetailDto.VehicleSwitch> switches = kept.stream().filter(r -> !r.brigade().isEmpty())
                .collect(Collectors.groupingBy(r -> r.day() + "|" + r.brigade(), TreeMap::new, Collectors.toList()))
                .values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> new LineDetailDto.VehicleSwitch(list.get(0).day(), list.get(0).brigade(),
                        list.stream().sorted(Comparator.comparing(VehicleDay::from))
                                .map(r -> new LineDetailDto.VehicleSpell(r.number(), r.from(), r.to())).toList()))
                .sorted(Comparator.comparing(LineDetailDto.VehicleSwitch::day, Comparator.reverseOrder())
                        .thenComparing(LineDetailDto.VehicleSwitch::brigade))
                .toList();

        int days = (int) rows.stream().map(VehicleDay::day).distinct().count();
        return new LineDetailDto.Vehicles(days, vehicles, brigades, switches);
    }
}
