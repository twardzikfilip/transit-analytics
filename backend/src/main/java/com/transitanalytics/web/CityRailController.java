package com.transitanalytics.web;

import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.service.CityRailService;
import com.transitanalytics.service.RailBusComparison;
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
    private final RailBusComparison comparison;

    public CityRailController(CityRailService rail, RailBusComparison comparison) {
        this.rail = rail;
        this.comparison = comparison;
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

    @Operation(summary = "Train against bus in the city",
            description = "For every pair of railway stations in the city: the fastest Polregio journey (leaving at "
                    + "any time) and the fastest bus journey leaving off-peak (weekdays 08:30–15:00 and "
                    + "17:00–20:00) between the bus stops nearest the two stations, each with up to two changes, "
                    + "on the weekday the trains are counted on. Changing trains takes 3 minutes; changing buses 1 "
                    + "minute, plus the walk to a stop up to 300 m away.")
    @GetMapping("/comparison")
    public CityRailDto.Comparison comparison() {
        return comparison.comparison();
    }
}
