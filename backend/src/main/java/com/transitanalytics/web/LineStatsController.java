package com.transitanalytics.web;

import com.transitanalytics.dto.LineStatsDto;
import com.transitanalytics.service.LineStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Line statistics", description = "Per-line figures from the weekday timetable")
@RestController
@RequestMapping("/api/lines")
public class LineStatsController {

    private final LineStatsService stats;

    public LineStatsController(LineStatsService stats) {
        this.stats = stats;
    }

    @Operation(summary = "Weekday statistics per line",
            description = "From the regular weekday timetable: length (main route, km), shortest and longest "
                    + "end-to-end travel time, average interval between departures and vehicles needed in peak "
                    + "(06:00–09:00, 15:00–17:00) and off-peak (17:00–22:00) hours.")
    @GetMapping("/stats")
    public LineStatsDto.Report weekdayStats() {
        return stats.weekdayReport();
    }
}
