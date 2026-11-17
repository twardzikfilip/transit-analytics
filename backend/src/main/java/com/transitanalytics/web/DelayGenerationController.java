package com.transitanalytics.web;

import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.service.DelayGenerationService;
import com.transitanalytics.service.ReportWindows;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Delay generation", description = "Where buses gain or recover delay between stops, from recorded positions")
@RestController
@RequestMapping("/api/delay-generation")
public class DelayGenerationController {

    private final DelayGenerationService delays;
    private final ReportWindows windows;

    public DelayGenerationController(DelayGenerationService delays, ReportWindows windows) {
        this.delays = delays;
        this.windows = windows;
    }

    @Operation(summary = "Delay generated on every stretch of the network",
            description = "In a time window, on the timetable's lines: the change in the "
                    + "feed's delay between leaving one stop and leaving the next (positive: delay generated), "
                    + "averaged per directed stretch (all lines together, measured at least 5 times) for the whole "
                    + "day and per part of it, and each line's average gain from first stop to last. Per-day "
                    + "figures are over the days with recordings. Cached for 2 minutes.")
    @GetMapping
    public DelayGenerationDto.Report report(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to) {
        return delays.report(windows.resolve(from, to));
    }

    @Operation(summary = "Delay generated along one line",
            description = "Per headsign: every stretch in route order with its average, median and 90th percentile "
                    + "change in delay, the share of runs gaining a minute or more, and the average per hour of the "
                    + "day; the line's brigades by delay gained per run; and example runs (the most delay gained "
                    + "overall, and on each stretch) with the delay on leaving every stop.")
    @GetMapping("/lines/{line}")
    public DelayGenerationDto.LineReport line(
            @PathVariable String line,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return delays.line(windows.resolve(from, to), line);
    }
}
