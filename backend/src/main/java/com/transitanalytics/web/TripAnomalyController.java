package com.transitanalytics.web;

import com.transitanalytics.common.ReportWindows;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.trips.FaultyTrackers;
import com.transitanalytics.trips.TripAnomalyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Tag(name = "Abnormal trips", description = "Scheduled trips not run, run only partly, or off their route")
@RestController
@RequestMapping("/api/trip-anomalies")
public class TripAnomalyController {

    private final TripAnomalyService anomalies;
    private final ReportWindows windows;
    private final FaultyTrackers trackers;

    public TripAnomalyController(TripAnomalyService anomalies, ReportWindows windows, FaultyTrackers trackers) {
        this.anomalies = anomalies;
        this.windows = windows;
        this.trackers = trackers;
    }

    @Operation(summary = "Abnormal trips scheduled to run in a time window",
            description = "Every trip of the timetable scheduled to run in the window (for any part of it), "
                    + "matched to the recorded positions by brigade, route and the scheduled time at the reported next "
                    + "stop (report time minus delay): counts of trips run in full, detours (3 reports in a row over "
                    + "150 m from the route shape), bus changed (run in full by two buses of the brigade, one taking "
                    + "over midway from the other), short-turned (the bus, reporting all along, joined midway, or "
                    + "left more than 3 stops before the end without driving on to it; within the last 3 stops only when "
                    + "it turned onto its brigade's next trip 3 min or more less late, as the feed often moves a bus on "
                    + "early there and buses go off to the depot), no signal (it stopped reporting partway but was back for its brigade's next "
                    + "trip), lost (it stopped reporting midway and didn't come back), missed (not run while the "
                    + "brigade ran other trips) and cancelled (no bus on the brigade all day). Trips during gaps in "
                    + "recording aren't judged. Per route, and the abnormal trips (the latest 2000) with their "
                    + "brigade, buses, stops covered, delays and what was seen. Cached for a minute.")
    @GetMapping
    public TripAnomalyDto.Report report(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to) {
        return anomalies.report(windows.resolve(from, to));
    }

    @Operation(summary = "Buses whose tracker probably fails",
            description = "Buses that, over the last 7 days, ran trips with parts missing (no signal: the bus stopped "
                    + "reporting partway and was back later) on at least 3 days, 5 trips or more and at least 10% "
                    + "of the trips it ran: on different days, that is the bus's tracker rather than chance. Most such trips first, with the trips "
                    + "the bus ran in all, the days, the routes and the latest one.")
    @GetMapping("/faulty-trackers")
    public TripAnomalyDto.FaultyTrackers faultyTrackers() {
        return trackers.faultyTrackers();
    }
}
