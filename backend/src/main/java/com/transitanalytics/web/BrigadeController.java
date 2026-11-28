package com.transitanalytics.web;

import com.transitanalytics.brigades.BrigadeDayService;
import com.transitanalytics.brigades.BrigadeScheduleService;
import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.dto.TripRunDto;
import com.transitanalytics.trips.TripRunService;
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
    private final TripRunService runs;
    private final Clock clock;

    public BrigadeController(BrigadeScheduleService schedules, BrigadeDayService days, TripRunService runs,
                             Clock clock) {
        this.schedules = schedules;
        this.days = days;
        this.runs = runs;
        this.clock = clock;
    }

    @Operation(summary = "Every brigade in the timetable",
            description = "An operator's brigades, whatever day they run, each with all the routes it runs; and every "
                    + "operator to choose from: the city's agency, and each other operator's timetable as one. "
                    + "Brigades other operators' timetables don't name are learnt from the recorded positions "
                    + "(414-1: live brigade 1 on routes 414–416); an operator with neither has none. The operator "
                    + "defaults to the city's (BKM).")
    @GetMapping
    public BrigadeDto.BrigadeList brigades(
            @Parameter(description = "Operator: the city's agency id (BKM, the default) or another operator's "
                    + "feed (wschodexpress)")
            @RequestParam(required = false) String agency) {
        return days.brigades(agency);
    }

    @Operation(summary = "A brigade's day: each trip scheduled and what actually ran",
            description = "Every trip of the brigade on the service day (default: today's) in departure order, with "
                    + "its outcome as on Abnormal trips (completed, detour, vehicle-changed, no-signal, short-turned, "
                    + "lost, missed, cancelled, not-recorded, pending) and what ran, from the recorded positions: "
                    + "each bus's part, from which stop to which, when (scheduled time plus the reported delay), "
                    + "and the parts nobody ran or no bus was seen on; the bus's delay and speed about every minute, "
                    + "and where it stood still for 3 min or more. With a summary: punctuality and buses used.")
    @GetMapping("/{brigade}/day")
    public TripRunDto.Day day(
            @Parameter(description = "Brigade, e.g. 103-05") @PathVariable("brigade") String brigade,
            @Parameter(description = "Service day, e.g. 2026-11-19; default today's")
            @RequestParam(required = false) LocalDate date) {
        return runs.day(date, brigade);
    }

    @Operation(summary = "A brigade's runs on a service day",
            description = "Every run of the brigade on the service day (03:00 to 03:00 Warsaw time) that `at` falls "
                    + "in (default: now), in departure order, with route, headsign, first and last stop, and "
                    + "scheduled times.")
    @GetMapping("/{brigade}/schedule")
    public BrigadeDto.Schedule schedule(
            @Parameter(description = "Brigade as live positions name it, e.g. 103-05") @PathVariable String brigade,
            @Parameter(description = "A moment of the service day wanted, ISO-8601; default now")
            @RequestParam(required = false) Instant at) {
        return schedules.schedule(brigade, at == null ? clock.instant() : at);
    }
}
