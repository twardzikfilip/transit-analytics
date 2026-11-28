package com.transitanalytics.web;

import com.transitanalytics.dto.RouteDetailDto;
import com.transitanalytics.dto.RouteStatsDto;
import com.transitanalytics.routes.RouteDetailService;
import com.transitanalytics.routes.RouteStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Routes", description = "The routes: their kinds, weekday figures, and one route in detail")
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteDetailService routes;
    private final RouteStatsService stats;

    public RouteController(RouteDetailService routes, RouteStatsService stats) {
        this.routes = routes;
        this.stats = stats;
    }

    @Operation(summary = "All routes",
            description = "The timetable's routes and those only seen on the live feed, in numeric order, then "
                    + "lettered ones (N1, …), each with its kind: urban, suburban, night_urban or regional.")
    @GetMapping
    public List<RouteDetailDto.RouteRef> routes() {
        return routes.routes();
    }

    @Operation(summary = "Weekday statistics per route",
            description = "From the regular weekday timetable: length (main route, km), average speed and interval "
                    + "between departures, regularity, and vehicles needed in peak (06:30–08:30, 15:00–17:00) and "
                    + "off-peak (08:30–15:00, 17:00–20:00) hours; recorded delays.")
    @GetMapping("/stats")
    public RouteStatsDto.Report stats() {
        return stats.weekdayReport();
    }

    @Operation(summary = "Timetable detail of a route",
            description = "From the regular weekday timetable: the variants (direction and shape, with the route "
                    + "and stops), base-route length, shortest and longest scheduled travel time and average speed, "
                    + "the part in fare zone 2, and every brigade's runs, start, end and breaks of 30 min or more.")
    @GetMapping("/{route}/detail")
    public RouteDetailDto.Detail detail(@Parameter(description = "Route GTFS id, e.g. 103") @PathVariable String route) {
        return routes.detail(route);
    }

    @Operation(summary = "Measured performance of a route",
            description = "From recorded vehicle positions on weekdays (local hours, after midnight as 24–26): "
                    + "travel time and average speed of full runs of the base route by departure hour (min, max, "
                    + "mean, median), average delay per 15 minutes, and speed between consecutive stops in peak hours.")
    @GetMapping("/{route}/performance")
    public RouteDetailDto.Performance performance(
            @Parameter(description = "Route GTFS id, e.g. 103") @PathVariable String route) {
        return routes.performance(route);
    }
}
