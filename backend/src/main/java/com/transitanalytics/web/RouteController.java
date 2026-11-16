package com.transitanalytics.web;

import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.service.RouteService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Routes")
@RestController
@RequestMapping("/api/routes")
public class RouteController extends CrudController<RouteDto.Request, RouteDto.Response> {

    public RouteController(RouteService service) {
        super(service);
    }
}
