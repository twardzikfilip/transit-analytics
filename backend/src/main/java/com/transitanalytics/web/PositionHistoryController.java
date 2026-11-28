package com.transitanalytics.web;

import com.transitanalytics.dwell.StopDwellService;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.common.ReportWindows;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.recording.PositionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    private final ReportWindows windows;
    private final StopDwellService dwell;

    public PositionHistoryController(PositionHistoryService history, ReportWindows windows, StopDwellService dwell) {
        this.history = history;
        this.windows = windows;
        this.dwell = dwell;
    }

    @Operation(summary = "Vehicles running and mean delay over time",
            description = "Per bucket of polls made in [from, to): vehicles running (average per poll), peak, reports "
                    + "and mean delay; plus totals. Buckets are 1 minute (one poll) for windows up to 24 hours and "
                    + "grow for longer ones (at most 1,500 points). Windows up to a year.")
    @GetMapping("/fleet")
    public PositionHistoryDto.FleetSeries fleet(
            @Parameter(description = "Start, inclusive (ISO-8601); the first recording when absent", example = "2026-11-16T05:00:00Z") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent", example = "2026-11-16T17:00:00Z") @RequestParam(required = false) Instant to,
            @Parameter(description = "Route, e.g. 103; all routes when left out") @RequestParam(required = false) String route,
            @Parameter(description = "Operator, e.g. BKM; all operators when left out") @RequestParam(required = false) String agency) {
        ReportWindow w = windows.resolve(from, to);
        return history.fleet(w.from(), w.to(), blankToNull(route), blankToNull(agency));
    }

    @Operation(summary = "Delay distribution",
            description = "Vehicles and reports per delay band: on time (under 2 min late, or early), 2–5, 5–10, "
                    + "and 10 min or more. A vehicle counts in every band it was in during the window.")
    @GetMapping("/delays")
    public List<PositionHistoryDto.DelayBand> delays(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                     @RequestParam(required = false) String route,
            @Parameter(description = "Operator, e.g. BKM; all operators when left out") @RequestParam(required = false) String agency) {
        ReportWindow w = windows.resolve(from, to);
        return history.delays(w.from(), w.to(), blankToNull(route), blankToNull(agency));
    }

    @Operation(summary = "Routes recorded in a time window", description = "For the route filter, in route order.")
    @GetMapping("/routes")
    public List<PositionHistoryDto.RouteActivity> routes(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @Parameter(description = "Operator, e.g. BKM; all operators when left out") @RequestParam(required = false) String agency) {
        ReportWindow w = windows.resolve(from, to);
        return history.routes(w.from(), w.to(), blankToNull(agency));
    }

    @Operation(summary = "The stops where buses dwell longest",
            description = "The 5 stops with the longest mean dwell in the window, of at least 20 visits. A visit is a "
                    + "bus's consecutive reports within 50 m of a stop along its route (its route's first and last "
                    + "stops left out): the time it halted there, 0 when it went past; halts over 10 minutes are "
                    + "breaks, left out. For one route, its stops; else each stop's visits on all routes (an "
                    + "operator's routes, when one is chosen). Worked out per service day and stored.")
    @GetMapping("/dwell")
    public List<PositionHistoryDto.StopDwell> dwell(@RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Route, e.g. 103; all routes when left out") @RequestParam(required = false) String route,
            @Parameter(description = "Operator, e.g. BKM; all operators when left out") @RequestParam(required = false) String agency) {
        ReportWindow w = windows.resolve(from, to);
        String one = blankToNull(route);
        String operator = blankToNull(agency);
        List<String> routes = one != null || operator == null ? null
                : history.routes(w.from(), w.to(), operator).stream().map(PositionHistoryDto.RouteActivity::route)
                .toList();
        return dwell.top(w, one, routes);
    }

    @Operation(summary = "Operators on the feed", description = "For the operator filter: the city operator first.")
    @GetMapping("/agencies")
    public List<String> agencies() {
        return history.agencies();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
