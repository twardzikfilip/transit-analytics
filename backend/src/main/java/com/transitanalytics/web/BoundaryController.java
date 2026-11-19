package com.transitanalytics.web;

import com.transitanalytics.classification.RouteClassProperties;
import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.dto.BoundaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "Boundaries", description = "City limits and city centre, for maps")
@RestController
@RequestMapping("/api/boundaries")
public class BoundaryController {

    private final BoundaryDto boundaries;

    public BoundaryController(RouteClassProperties routeClasses) {
        this.boundaries = new BoundaryDto(CityBoundaries.CITY, CityBoundaries.CENTRE,
                routeClasses.geometry().centreRing(CityBoundaries.CENTRE));
    }

    @Operation(summary = "Białystok's city limits and centre",
            description = "Closed rings of [latitude, longitude] pairs: the city (OpenStreetMap relation 2910925) and "
                    + "Osiedle Centrum (relation 4091287), © OpenStreetMap contributors, ODbL; and the smaller "
                    + "centre lines are classified by (lineCentre, set in classification/routes.yml).")
    @GetMapping
    public ResponseEntity<BoundaryDto> boundaries() {
        // Fixed while the app runs: let browsers keep it for an hour.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(boundaries);
    }
}
