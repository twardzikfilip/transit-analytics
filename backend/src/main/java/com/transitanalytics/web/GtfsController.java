package com.transitanalytics.web;

import com.transitanalytics.gtfs.GtfsBialystokService;
import com.transitanalytics.gtfs.GtfsImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GTFS import", description = "Load public transport timetables (GTFS) into the database")
@RestController
@RequestMapping("/api/gtfs")
public class GtfsController {

    private final GtfsBialystokService bialystok;

    public GtfsController(GtfsBialystokService bialystok) {
        this.bialystok = bialystok;
    }

    @Operation(summary = "Import a timetable feed",
            description = "Downloads a GTFS feed and replaces what it loaded before (agencies, routes, stops, trips, "
                    + "stop times, calendar dates and shapes) with it: \"bialystok\" is the city's (komunikacja.bialystok.pl); "
                    + "other operators' feeds are configured under app.gtfs.extra (e.g. \"pksnova-500\", PKS Nova line "
                    + "500) and loaded with their ids prefixed, so imports of one feed never touch another's. Admin only; "
                    + "takes a few seconds.")
    @ApiResponse(responseCode = "200", description = "Imported; row counts per table")
    @ApiResponse(responseCode = "404", description = "No such feed")
    @ApiResponse(responseCode = "409", description = "An import is already running")
    @ApiResponse(responseCode = "422", description = "The feed is incomplete or invalid; nothing was changed")
    @ApiResponse(responseCode = "502", description = "The feed could not be downloaded; nothing was changed")
    @PostMapping("/{feed}/import")
    public GtfsImportResult importFeed(@PathVariable("feed") String feed) {
        return bialystok.importFeed(feed);
    }
}
