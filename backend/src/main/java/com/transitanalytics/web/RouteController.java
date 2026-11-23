package com.transitanalytics.web;

import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.service.LineSubtypes;
import com.transitanalytics.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Routes (GTFS)")
@RestController
@RequestMapping("/api/routes")
public class RouteController extends CrudController<RouteDto.Request, RouteDto.Response, RouteDto.Filter> {

    private final LineSubtypes subtypes;

    public RouteController(RouteService service, LineSubtypes subtypes) {
        super(service);
        this.subtypes = subtypes;
    }

    @Operation(summary = "Every line's kind",
            description = "Each line of the timetable by name (as live positions name it), with its kind: urban, "
                    + "suburban, night_urban or regional. Pages colour line labels by it.")
    @GetMapping("/subtypes")
    public Map<String, RouteSubtype> subtypes() {
        return subtypes.all();
    }
}
