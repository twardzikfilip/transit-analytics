package com.transitanalytics.web;

import com.transitanalytics.dto.TimetableDto;
import com.transitanalytics.timetable.StopTimetable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Timetable (GTFS)")
@RestController
public class StopController {

    private final StopTimetable timetable;

    public StopController(StopTimetable timetable) {
        this.timetable = timetable;
    }

    @Operation(summary = "Stops, by name", description = "Trains' stations left out. q: text in the name or code.")
    @GetMapping("/api/stops")
    public TimetableDto.Page<TimetableDto.Stop> stops(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String zoneId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return timetable.stops(q, zoneId, Math.max(0, page), clamp(size));
    }

    @Operation(summary = "Stop times", description = "A trip's calls in order, or a stop's by departure. Trains left out.")
    @GetMapping("/api/stop-times")
    public TimetableDto.Page<TimetableDto.StopTime> stopTimes(@RequestParam(required = false) Long tripId,
                                                              @RequestParam(required = false) Long stopId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        return timetable.stopTimes(tripId, stopId, Math.max(0, page), clamp(size));
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, 500));
    }
}
