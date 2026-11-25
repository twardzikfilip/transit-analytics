package com.transitanalytics.web;

import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.service.CityRailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "City rail", description = "Railways in and around Białystok, and the Polregio and PKP Intercity trains on them")
@RestController
@RequestMapping("/api/city-rail")
public class CityRailController {

    private final CityRailService rail;

    public CityRailController(CityRailService rail) {
        this.rail = rail;
    }

    @Operation(summary = "Railways and the trains on them",
            description = "The railways (© OpenStreetMap contributors, ODbL) and a route from Białystok to each end of "
                    + "the network (Łapy, Knyszyn, Czarna Białostocka, Lewickie) with all trains on it, wherever they "
                    + "run beyond it: their destinations, and the trains from and to Białystok on the next weekday, "
                    + "Saturday and Sunday, Polregio's and together with PKP Intercity's. A through train is on both "
                    + "its routes.")
    @GetMapping
    public CityRailDto.Report report() {
        return rail.report();
    }
}
