package com.transitanalytics.web;

import com.transitanalytics.dto.AgencyDto;
import com.transitanalytics.service.NoFilter;
import com.transitanalytics.service.AgencyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agencies (GTFS)")
@RestController
@RequestMapping("/api/agencies")
public class AgencyController extends CrudController<AgencyDto.Request, AgencyDto.Response, NoFilter> {

    public AgencyController(AgencyService service) {
        super(service);
    }
}
