package com.transitanalytics.web;

import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.recording.PositionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Tag(name = "Position history", description = "Recorded vehicle positions (time series), by time window")
@RestController
@RequestMapping("/api/positions")
public class PositionHistoryController {

    private final PositionHistoryService history;

    public PositionHistoryController(PositionHistoryService history) {
        this.history = history;
    }

    @Operation(summary = "Vehicles running and mean delay over time",
            description = "Per bucket of polls made in [from, to): vehicles running (average per poll), peak, reports "
                    + "and mean delay; plus totals. Buckets are 1 minute (one poll) for windows up to 24 hours and "
                    + "grow for longer ones (at most 1,500 points). Windows up to 31 days.")
    @GetMapping("/fleet")
    public PositionHistoryDto.FleetSeries fleet(
            @Parameter(description = "Start, inclusive (ISO-8601)", example = "2026-11-16T05:00:00Z") @RequestParam Instant from,
            @Parameter(description = "End, exclusive (ISO-8601)", example = "2026-11-16T17:00:00Z") @RequestParam Instant to,
            @Parameter(description = "Line, e.g. 103; all lines when left out") @RequestParam(required = false) String route) {
        return history.fleet(from, to, blankToNull(route));
    }

    @Operation(summary = "Delay distribution",
            description = "Vehicles and reports per delay band: under 1 min (incl. early), 1–2, 2–3, … 9–10, "
                    + "10 min or more. A vehicle counts in every band it was in during the window.")
    @GetMapping("/delays")
    public List<PositionHistoryDto.DelayBand> delays(@RequestParam Instant from, @RequestParam Instant to,
                                                     @RequestParam(required = false) String route) {
        return history.delays(from, to, blankToNull(route));
    }

    @Operation(summary = "Replay of vehicle positions",
            description = "Animation frames for [from, to) (at most 7 days): per step the latest position of every "
                    + "vehicle, as compact rows [vehicleIndex, latitude, longitude, delaySeconds, lineIndex]. The "
                    + "step is 1 minute (one poll) for short windows and grows to keep at most 360 frames. Repeat "
                    + "route for several lines; leave it out for all.")
    @GetMapping("/replay")
    public PositionHistoryDto.Replay replay(@RequestParam Instant from, @RequestParam Instant to,
                                            @RequestParam(required = false) List<String> route) {
        return history.replay(from, to, route);
    }

    @Operation(summary = "Lines recorded in a time window", description = "For the line filter, in line order.")
    @GetMapping("/routes")
    public List<PositionHistoryDto.RouteActivity> routes(@RequestParam Instant from, @RequestParam Instant to) {
        return history.routes(from, to);
    }

    @Operation(summary = "Recorded position reports",
            description = "Paged (size up to 500). Sort by time, vehicle, line, duty, towards, delay or nextStop, "
                    + "with ,asc or ,desc (default time,desc); ties are newest first.")
    @GetMapping
    public Page<PositionHistoryDto.Sample> samples(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String route,
            @Parameter(description = "Vehicle number, e.g. 230") @RequestParam(required = false) String vehicle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "e.g. delay,desc", example = "time,desc")
            @RequestParam(defaultValue = "time,desc") String sort,
            @Parameter(description = "Leave out outliers: reports more than 30 min late (likely feed errors)")
            @RequestParam(defaultValue = "false") boolean excludeOutliers) {
        return history.samples(from, to, blankToNull(route), blankToNull(vehicle), page, size, sort, excludeOutliers);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
