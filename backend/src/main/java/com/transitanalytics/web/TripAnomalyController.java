package com.transitanalytics.web;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TimeWindow;
import com.transitanalytics.service.TripAnomalyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Abnormal trips", description = "Scheduled trips not run, run only partly, or off their route")
@RestController
@RequestMapping("/api/trip-anomalies")
public class TripAnomalyController {

    private final TripAnomalyService anomalies;

    public TripAnomalyController(TripAnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @Operation(summary = "Abnormal trips scheduled to run in a time window",
            description = "Every trip of the timetable scheduled to run in the window (for any part of it), "
                    + "matched to the recorded positions by brigade, line and the scheduled time at the reported next "
                    + "stop (report time minus delay): counts of trips run in full, detours (3 reports in a row over "
                    + "150 m from the route shape), bus changed (run in full by two buses of the brigade, one taking "
                    + "over midway from the other), short-turned (the bus, reporting all along, joined midway or left "
                    + "before the end), no signal (it stopped reporting partway but was back for its brigade's next "
                    + "trip), lost (it stopped reporting midway and didn't come back), missed (not run while the "
                    + "brigade ran other trips) and cancelled (no bus on the brigade all day). Trips during gaps in "
                    + "recording aren't judged. Per line, and the abnormal trips (the latest 2000) with their "
                    + "brigade, buses, stops covered, delays and what was seen. Cached for a minute.")
    @GetMapping
    public TripAnomalyDto.Report report(
            @Parameter(description = "15m, 1h, 3h, 6h, day, week or month")
            @RequestParam(defaultValue = "day") String window) {
        return anomalies.report(TimeWindow.of(window));
    }
}
