package com.transitanalytics.web;

import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.service.DelayGenerationService;
import com.transitanalytics.service.TimeWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delay generation", description = "Where buses gain or recover delay between stops, from recorded positions")
@RestController
@RequestMapping("/api/delay-generation")
public class DelayGenerationController {

    private final DelayGenerationService delays;

    public DelayGenerationController(DelayGenerationService delays) {
        this.delays = delays;
    }

    @Operation(summary = "Delay generated on every stretch of the network",
            description = "For the last 24 hours, 7 days or 30 days, on the timetable's lines: the change in the "
                    + "feed's delay between leaving one stop and leaving the next (positive: delay generated), "
                    + "averaged per directed stretch (all lines together, measured at least 5 times) for the whole "
                    + "day and per part of it, and each line's average gain from first stop to last. Per-day "
                    + "figures are over the days with recordings. Cached for 2 minutes.")
    @GetMapping
    public DelayGenerationDto.Report report(
            @Parameter(description = "day, week or month") @RequestParam(defaultValue = "week") String window) {
        return delays.report(TimeWindow.of(window));
    }

    @Operation(summary = "Delay generated along one line",
            description = "Per headsign: every stretch in route order with its average, median and 90th percentile "
                    + "change in delay, the share of runs gaining a minute or more, and the average per hour of the "
                    + "day; the line's brigades by delay gained per run; and example runs (the most delay gained "
                    + "overall, and on each stretch) with the delay on leaving every stop.")
    @GetMapping("/lines/{line}")
    public DelayGenerationDto.LineReport line(
            @PathVariable String line,
            @Parameter(description = "day, week or month") @RequestParam(defaultValue = "week") String window) {
        return delays.line(TimeWindow.of(window), line);
    }
}
