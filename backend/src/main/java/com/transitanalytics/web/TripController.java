package com.transitanalytics.web;

import com.transitanalytics.dto.TripDto;
import com.transitanalytics.service.TripService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Trips")
@RestController
@RequestMapping("/api/trips")
public class TripController extends CrudController<TripDto.Request, TripDto.Response> {

    public TripController(TripService service) {
        super(service);
    }
}
