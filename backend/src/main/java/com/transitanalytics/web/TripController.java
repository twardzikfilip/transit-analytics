package com.transitanalytics.web;

import com.transitanalytics.dto.TripDto;
import com.transitanalytics.service.TripService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
public class TripController extends CrudController<TripDto.Request, TripDto.Response> {

    public TripController(TripService service) {
        super(service);
    }
}
