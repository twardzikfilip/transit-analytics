package com.transitanalytics.web;

import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.service.RouteService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
public class RouteController extends CrudController<RouteDto.Request, RouteDto.Response> {

    public RouteController(RouteService service) {
        super(service);
    }
}
