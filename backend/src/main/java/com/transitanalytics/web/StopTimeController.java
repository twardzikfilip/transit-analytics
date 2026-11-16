package com.transitanalytics.web;

import com.transitanalytics.dto.StopTimeDto;
import com.transitanalytics.service.StopTimeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stop times (GTFS)")
@RestController
@RequestMapping("/api/stop-times")
public class StopTimeController extends CrudController<StopTimeDto.Request, StopTimeDto.Response, StopTimeDto.Filter> {

    public StopTimeController(StopTimeService service) {
        super(service);
    }
}
