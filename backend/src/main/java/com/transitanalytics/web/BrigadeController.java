package com.transitanalytics.web;

import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.service.BrigadeDayService;
import com.transitanalytics.service.BrigadeScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Tag(name = "Brigades", description = "Vehicle duties (brigades) from the timetable")
@RestController
@RequestMapping("/api/brigades")
public class BrigadeController {

    private final BrigadeScheduleService schedules;
    private final BrigadeDayService days;
    private final Clock clock;

    public BrigadeController(BrigadeScheduleService schedules, BrigadeDayService days, Clock clock) {
        this.schedules = schedules;
        this.days = days;
        this.clock = clock;
    }

    @Operation(summary = "The brigades running on a service day",
            description = "An agency's brigades on the service day (default: today's), each with its lines, trips, "
                    + "first departure and last arrival; and the agencies to choose from (those whose timetable "
                    + "names brigades). The agency defaults to the city's (BKM).")
    @GetMapping
    public BrigadeDto.DayList brigades(
            @Parameter(description = "Service day, e.g. 2026-11-19; default today's")
            @RequestParam(required = false) LocalDate date,
            @Parameter(description = "Agency's timetable id, e.g. BKM; default the city's")
            @RequestParam(required = false) String agency) {
        return days.brigades(date == null ? days.today() : date, agency);
    }

    @Operation(summary = "A brigade's day: each trip scheduled and what actually ran",
            description = "Every trip of the brigade on the service day (default: today's) in departure order, with "
                    + "its outcome as on Abnormal trips (completed, detour, vehicle-changed, no-signal, short-turned, "
                    + "lost, missed, cancelled, not-recorded, pending) and what ran, from the recorded positions: "
                    + "each bus's part, from which stop to which, when (scheduled time plus the reported delay), "
                    + "and the parts nobody ran or no bus was seen on. With a summary: punctuality and buses used.")
    @GetMapping("/{brigade}/day")
    public BrigadeDto.Day day(
            @Parameter(description = "Brigade, e.g. 103-05") @PathVariable("brigade") String brigade,
            @Parameter(description = "Service day, e.g. 2026-11-19; default today's")
            @RequestParam(required = false) LocalDate date) {
        return days.day(brigade, date == null ? days.today() : date);
    }

    @Operation(summary = "A brigade's runs on a service day",
            description = "Every run of the brigade on the service day (03:00 to 03:00 Warsaw time) that `at` falls "
                    + "in (default: now), in departure order, with line, headsign, first and last stop, and "
                    + "scheduled times.")
    @GetMapping("/{brigade}/schedule")
    public BrigadeDto.Schedule schedule(
            @Parameter(description = "Brigade as live positions name it, e.g. 103-05") @PathVariable String brigade,
            @Parameter(description = "A moment of the service day wanted, ISO-8601; default now")
            @RequestParam(required = false) Instant at) {
        return schedules.schedule(brigade, at == null ? clock.instant() : at);
    }
}
