package com.transitanalytics.web;

import com.transitanalytics.gtfs.GtfsBialystokService;
import com.transitanalytics.gtfs.GtfsImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "Import the Białystok timetable",
            description = "Downloads the current GTFS feed from komunikacja.bialystok.pl and replaces agencies, routes, "
                    + "stops, trips, stop times, calendar dates and shapes with it. Admin only; takes a few seconds.")
    @ApiResponse(responseCode = "200", description = "Imported; row counts per table")
    @ApiResponse(responseCode = "409", description = "An import is already running")
    @ApiResponse(responseCode = "422", description = "The feed is incomplete or invalid; nothing was changed")
    @ApiResponse(responseCode = "502", description = "The feed could not be downloaded; nothing was changed")
    @PostMapping("/bialystok/import")
    public GtfsImportResult importBialystok() {
        return bialystok.importFeed();
    }
}
