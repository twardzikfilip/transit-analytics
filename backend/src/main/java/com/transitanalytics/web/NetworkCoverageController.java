package com.transitanalytics.web;

import com.transitanalytics.dto.NetworkCoverageDto;
import com.transitanalytics.service.NetworkCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Network coverage", description = "How much of the city is near a stop; districts' population density")
@RestController
@RequestMapping("/api/network-coverage")
public class NetworkCoverageController {

    private final NetworkCoverageService coverage;

    public NetworkCoverageController(NetworkCoverageService coverage) {
        this.coverage = coverage;
    }

    @Operation(summary = "Stop coverage of the city, its woods and its districts' population densities",
            description = "The city's border and area; the share of it within 400 m of a stop of the city's lines, "
                    + "of all of it and of the part outside its woods (OpenStreetMap landuse=forest and "
                    + "natural=wood); the woods with their areas; and each district (osiedle) with its area, "
                    + "registered residents and density, also without its woods.")
    @GetMapping
    public NetworkCoverageDto coverage() {
        return coverage.coverage();
    }
}
