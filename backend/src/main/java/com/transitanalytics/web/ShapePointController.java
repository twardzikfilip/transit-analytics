package com.transitanalytics.web;

import com.transitanalytics.dto.ShapePointDto;
import com.transitanalytics.service.ShapePointService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shape points (GTFS)")
@RestController
@RequestMapping("/api/shape-points")
public class ShapePointController extends CrudController<ShapePointDto.Request, ShapePointDto.Response, ShapePointDto.Filter> {

    public ShapePointController(ShapePointService service) {
        super(service);
    }
}
