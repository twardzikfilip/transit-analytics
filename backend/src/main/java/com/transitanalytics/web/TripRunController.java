package com.transitanalytics.web;

import com.transitanalytics.dto.TripRunDto;
import com.transitanalytics.service.ReportWindows;
import com.transitanalytics.service.TripRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@Tag(name = "Trips", description = "Trips scheduled in a time window and how they ran")
@RestController
@RequestMapping("/api/trip-runs")
public class TripRunController {

    private final TripRunService trips;
    private final ReportWindows windows;

    public TripRunController(TripRunService trips, ReportWindows windows) {
        this.trips = trips;
        this.windows = windows;
    }

    @Operation(summary = "Trips scheduled in a time window, each with what ran",
            description = "Every trip of the timetable scheduled to run (for any part) in the window, in departure "
                    + "order, 50 a page: its outcome as on Abnormal trips, what ran (each bus's part, from which stop "
                    + "to which, when), the bus's delay and speed about every minute, and where it stood still for 3 "
                    + "min or more on the way. Of a window longer than 24 h, its last 24 h. The search finds a bus "
                    + "(its number), a brigade or a line (exactly), or part of a destination.")
    @GetMapping
    public TripRunDto.Page trips(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to,
            @Parameter(description = "Bus number, brigade, line, or part of a destination") @RequestParam(required = false) String q,
            @Parameter(description = "Page, from 0") @RequestParam(defaultValue = "0") int page) {
        return trips.page(windows.resolve(from, to), q, Math.max(0, page));
    }

    @Operation(summary = "One trip in detail: every report, speed, and the stops with how long the bus stood",
            description = "Every report of the buses on one trip while they ran it (as on the brigade's day), with "
                    + "how late each was and its speed (0 standing still: under 5 m, or 0.4 m/s, since the report "
                    + "before; else the average over 15 s either side), the trip's route shape, and its stops with "
                    + "where they are and how long a bus halted within 50 m of each: its steps there under 7 km/h (reports "
                    + "come every 10 s, longer than a short halt), 0 when all were 12 km/h or more, null when that "
                    + "can't be told. Asked for when a trip is opened.")
    @GetMapping("/{date}/{brigade}/{tripId}")
    public TripRunDto.Detail detail(
            @Parameter(description = "Service day, e.g. 2026-11-23") @PathVariable LocalDate date,
            @Parameter(description = "Brigade, e.g. 103-05") @PathVariable String brigade,
            @Parameter(description = "Trip id in the timetable") @PathVariable String tripId) {
        return trips.detail(date, brigade, tripId);
    }
}
