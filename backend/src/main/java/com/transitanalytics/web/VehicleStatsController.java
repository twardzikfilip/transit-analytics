package com.transitanalytics.web;

import com.transitanalytics.dto.VehicleStatsDto;
import com.transitanalytics.service.VehicleStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Vehicle statistics", description = "How the vehicles were used, from recorded positions")
@RestController
@RequestMapping("/api/vehicle-stats")
public class VehicleStatsController {

    private final VehicleStatsService stats;

    public VehicleStatsController(VehicleStatsService stats) {
        this.stats = stats;
    }

    @Operation(summary = "Vehicle usage in a time window",
            description = "For the last 24 hours, 7 days or 30 days: hours in service and lines served per vehicle "
                    + "(top and bottom 10), vehicles that served several lines on one day, and vehicles that "
                    + "stopped serving their brigade while it was scheduled to run (switched to another brigade or "
                    + "disappeared). Hours count each report until the vehicle's next one, up to a minute. Cached for "
                    + "2 minutes.")
    @GetMapping
    public VehicleStatsDto.Report report(
            @Parameter(description = "day, week or month") @RequestParam(defaultValue = "week") String window) {
        return stats.report(VehicleStatsService.Window.of(window));
    }
}
