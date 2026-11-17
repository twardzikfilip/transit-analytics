package com.transitanalytics.web;

import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.service.HotspotService;
import com.transitanalytics.service.ReportWindows;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Stop hotspots", description = "Where buses stand still away from stops, from recorded positions")
@RestController
@RequestMapping("/api/hotspots")
public class HotspotController {

    private final HotspotService hotspots;
    private final ReportWindows windows;

    public HotspotController(HotspotService hotspots, ReportWindows windows) {
        this.hotspots = hotspots;
        this.windows = windows;
    }

    @Operation(summary = "Places where buses stand still away from stops",
            description = "In a time window, on the timetable's lines: standstills (two or "
                    + "more reports in a row within 10 m, up to 5 minutes) more than 50 m from any stop and 150 m from any terminal, clustered "
                    + "(DBSCAN, 25 m, 5 standstills, 3 vehicles) into hotspots: traffic lights, junctions, "
                    + "congestion. The most time lost first, with the lines, directions and busiest hour; per-day "
                    + "figures are over the days with recordings. Cached for 2 minutes.")
    @GetMapping
    public HotspotDto.Report report(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to,
            @Parameter(description = "Only this line's standstills; all lines when absent")
            @RequestParam(required = false) String line) {
        return hotspots.report(windows.resolve(from, to), line == null || line.isBlank() ? null : line);
    }
}
