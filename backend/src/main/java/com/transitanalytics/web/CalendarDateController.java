package com.transitanalytics.web;

import com.transitanalytics.dto.CalendarDateDto;
import com.transitanalytics.service.CalendarDateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Calendar dates (GTFS)")
@RestController
@RequestMapping("/api/calendar-dates")
public class CalendarDateController extends CrudController<CalendarDateDto.Request, CalendarDateDto.Response, CalendarDateDto.Filter> {

    public CalendarDateController(CalendarDateService service) {
        super(service);
    }
}
