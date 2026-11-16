package com.transitanalytics.web;

import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.service.LineDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Line detail", description = "One line in detail: timetable figures and measured performance")
@RestController
@RequestMapping("/api/lines")
public class LineDetailController {

    private final LineDetailService lines;

    public LineDetailController(LineDetailService lines) {
        this.lines = lines;
    }

    @Operation(summary = "All lines", description = "Lines in numeric order, then lettered ones (N1, …), with subtype.")
    @GetMapping
    public List<LineDetailDto.LineRef> lines() {
        return lines.lines();
    }

    @Operation(summary = "Timetable detail of a line",
            description = "From the regular weekday timetable: the variants (direction and shape, with the route "
                    + "and stops), base-route length, shortest and longest scheduled travel time and average speed, "
                    + "the part in fare zone 2, and every brigade's runs, start, end and breaks of 30 min or more.")
    @GetMapping("/{line}/detail")
    public LineDetailDto.Detail detail(@Parameter(description = "Route GTFS id, e.g. 103") @PathVariable String line) {
        return lines.detail(line);
    }

    @Operation(summary = "Measured performance of a line",
            description = "From recorded vehicle positions on weekdays (local hours, after midnight as 24–26): "
                    + "travel time and average speed of full runs of the base route by departure hour (min, max, "
                    + "mean, median), average delay per 15 minutes, and speed between consecutive stops in peak hours.")
    @GetMapping("/{line}/performance")
    public LineDetailDto.Performance performance(
            @Parameter(description = "Route GTFS id, e.g. 103") @PathVariable String line) {
        return lines.performance(line);
    }
}
