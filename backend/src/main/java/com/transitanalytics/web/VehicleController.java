package com.transitanalytics.web;

import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.VehicleService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController extends CrudController<VehicleDto.Request, VehicleDto.Response> {

    public VehicleController(VehicleService service) {
        super(service);
    }
}
