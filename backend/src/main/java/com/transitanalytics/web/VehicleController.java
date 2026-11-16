package com.transitanalytics.web;

import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.service.VehicleService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Vehicles")
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController extends CrudController<VehicleDto.Request, VehicleDto.Response> {

    public VehicleController(VehicleService service) {
        super(service);
    }
}
