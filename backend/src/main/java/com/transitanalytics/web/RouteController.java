package com.transitanalytics.web;

import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.service.RouteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Routes (GTFS)")
@RestController
@RequestMapping("/api/routes")
public class RouteController extends CrudController<RouteDto.Request, RouteDto.Response, RouteDto.Filter> {

    public RouteController(RouteService service) {
        super(service);
    }
}
