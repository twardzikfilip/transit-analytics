package com.transitanalytics.web;

import com.transitanalytics.dto.TripDto;
import com.transitanalytics.service.TripService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Trips (GTFS)")
@RestController
@RequestMapping("/api/trips")
public class TripController extends CrudController<TripDto.Request, TripDto.Response, TripDto.Filter> {

    public TripController(TripService service) {
        super(service);
    }
}
