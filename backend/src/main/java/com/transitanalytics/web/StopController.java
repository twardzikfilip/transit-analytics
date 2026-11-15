package com.transitanalytics.web;

import com.transitanalytics.dto.StopDto;
import com.transitanalytics.service.StopService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stops")
public class StopController extends CrudController<StopDto.Request, StopDto.Response> {

    public StopController(StopService service) {
        super(service);
    }
}
