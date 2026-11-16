package com.transitanalytics.web;

import com.transitanalytics.dto.StopDto;
import com.transitanalytics.service.StopService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stops")
@RestController
@RequestMapping("/api/stops")
public class StopController extends CrudController<StopDto.Request, StopDto.Response> {

    public StopController(StopService service) {
        super(service);
    }
}
