package com.transitanalytics.web;

import com.transitanalytics.dto.TimetableCoverageDto;
import com.transitanalytics.gtfs.GtfsFeedService;
import com.transitanalytics.gtfs.GtfsImportResult;
import com.transitanalytics.service.TimetableCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GTFS import", description = "Load public transport timetables (GTFS) into the database")
@RestController
@RequestMapping("/api/gtfs")
public class GtfsController {

    private final GtfsFeedService feeds;
    private final TimetableCoverageService coverage;

    public GtfsController(GtfsFeedService feeds, TimetableCoverageService coverage) {
        this.feeds = feeds;
        this.coverage = coverage;
    }

    @Operation(summary = "Until when the timetables run",
            description = "Each feed's last day with trips and the days left, and whether it is ending (5 days or fewer "
                    + "left, ended, or not loaded).")
    @GetMapping("/coverage")
    public TimetableCoverageDto coverage() {
        return coverage.coverage();
    }

    @Operation(summary = "Import a timetable feed",
            description = "Downloads a GTFS feed and replaces what it loaded before (agencies, routes, stops, trips, "
                    + "stop times, calendar dates and shapes) with it: \"bialystok\" is the city's (komunikacja.bialystok.pl); "
                    + "other operators' feeds are configured under app.gtfs.extra (\"pksnova-500\", \"wschodexpress\", "
                    + "\"turosn-koscielna\"). Each row records its feed, so imports of one feed never touch another's; "
                    + "another feed's stops that are the city's (their code is its stop id) are the city's. Admin only; "
                    + "takes a few seconds.")
    @ApiResponse(responseCode = "200", description = "Imported; row counts per table")
    @ApiResponse(responseCode = "404", description = "No such feed")
    @ApiResponse(responseCode = "409", description = "An import is already running")
    @ApiResponse(responseCode = "422", description = "The feed is incomplete or invalid; nothing was changed")
    @ApiResponse(responseCode = "502", description = "The feed could not be downloaded; nothing was changed")
    @PostMapping("/{feed}/import")
    public GtfsImportResult importFeed(@PathVariable("feed") String feed) {
        return feeds.importFeed(feed);
    }
}
