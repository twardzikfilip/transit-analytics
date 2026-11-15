package com.transitanalytics.web;

import com.transitanalytics.dto.VehiclePositionDto;
import com.transitanalytics.service.VehiclePositionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/vehicle-positions")
public class VehiclePositionController
        extends CrudController<VehiclePositionDto.Request, VehiclePositionDto.Response> {

    private final VehiclePositionService positions;

    public VehiclePositionController(VehiclePositionService service) {
        super(service);
        this.positions = service;
    }

    /** Latest known position of every vehicle — used by the live map. */
    @GetMapping("/latest")
    public List<VehiclePositionDto.Response> latest() {
        return positions.findLatestPerVehicle();
    }
}
