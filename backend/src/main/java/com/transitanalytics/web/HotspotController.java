package com.transitanalytics.web;

import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.service.HotspotService;
import com.transitanalytics.service.TimeWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stop hotspots", description = "Where buses stand still away from stops, from recorded positions")
@RestController
@RequestMapping("/api/hotspots")
public class HotspotController {

    private final HotspotService hotspots;

    public HotspotController(HotspotService hotspots) {
        this.hotspots = hotspots;
    }

    @Operation(summary = "Places where buses stand still away from stops",
            description = "For the last 24 hours, 7 days or 30 days, on the timetable's lines: standstills (two or "
                    + "more reports in a row within 10 m, up to 5 minutes) more than 50 m from any stop and 150 m from any terminal, clustered "
                    + "(DBSCAN, 25 m, 5 standstills, 3 vehicles) into hotspots: traffic lights, junctions, "
                    + "congestion. The most time lost first, with the lines, directions and busiest hour; per-day "
                    + "figures are over the days with recordings. Cached for 2 minutes.")
    @GetMapping
    public HotspotDto.Report report(
            @Parameter(description = "day, week or month") @RequestParam(defaultValue = "week") String window,
            @Parameter(description = "Only this line's standstills; all lines when absent")
            @RequestParam(required = false) String line) {
        return hotspots.report(TimeWindow.of(window), line == null || line.isBlank() ? null : line);
    }
}
