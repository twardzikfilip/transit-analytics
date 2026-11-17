package com.transitanalytics.web;

import com.transitanalytics.dto.FleetDto;
import com.transitanalytics.service.FleetService;
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
import java.util.List;
import java.util.Set;

@Tag(name = "Fleet", description = "How the fleet's buses were used, from recorded positions")
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetService fleet;
    private final ReportWindows windows;

    public FleetController(FleetService fleet, ReportWindows windows) {
        this.fleet = fleet;
        this.windows = windows;
    }

    @Operation(summary = "The fleet in a time window",
            description = "Buses in use and in the fleet (every bus ever seen on the live feed), per model; the 10 "
                    + "most and least used; every bus's hours in service, the longest first; and the buses that "
                    + "broke down (replaced midway through a trip and not used again that day, as on Abnormal "
                    + "trips), with when they served a line again. Hours count each report on a line until the "
                    + "bus's next one, up to a minute. Cached for 2 minutes. The figures can be limited to some "
                    + "operators' buses.")
    @GetMapping
    public FleetDto.Report report(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to,
            @Parameter(description = "Operators whose buses to include (repeat the parameter); all when absent")
            @RequestParam(required = false) List<String> agency) {
        return fleet.report(windows.resolve(from, to), agency == null ? Set.of() : Set.copyOf(agency));
    }

    @Operation(summary = "One bus in a time window",
            description = "Its runs (consecutive reports on one line, brigade and headsign) and breaks of 30 minutes "
                    + "or more per service day, hours in service per day, and the abnormal trips it was involved in: "
                    + "trips it broke down on, and its brigade's trips it missed.")
    @GetMapping("/vehicles/{id}")
    public FleetDto.VehicleDetail vehicle(@PathVariable long id,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to) {
        return fleet.vehicle(id, windows.resolve(from, to));
    }
}
