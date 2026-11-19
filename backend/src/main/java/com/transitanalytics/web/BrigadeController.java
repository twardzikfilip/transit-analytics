package com.transitanalytics.web;

import com.transitanalytics.dto.BrigadeDto;
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

@Tag(name = "Brigades", description = "Vehicle duties (brigades) from the timetable")
@RestController
@RequestMapping("/api/brigades")
public class BrigadeController {

    private final BrigadeScheduleService schedules;
    private final Clock clock;

    public BrigadeController(BrigadeScheduleService schedules, Clock clock) {
        this.schedules = schedules;
        this.clock = clock;
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
