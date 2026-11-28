package com.transitanalytics.web;

import com.transitanalytics.common.NotFoundException;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.dto.CityRailProposalDto;
import com.transitanalytics.rail.CityRailService;
import com.transitanalytics.rail.RailBusComparison;
import com.transitanalytics.rail.proposal.CityRailProposal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "City rail", description = "Railways in and around Białystok, and the Polregio and PKP Intercity trains on them")
@RestController
@RequestMapping("/api/city-rail")
public class CityRailController {

    private final CityRailService rail;
    private final RailBusComparison comparison;
    private final CityRailProposal proposal;

    public CityRailController(CityRailService rail, RailBusComparison comparison, CityRailProposal proposal) {
        this.rail = rail;
        this.comparison = comparison;
        this.proposal = proposal;
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

    @Operation(summary = "City Rail proposal no. 1",
            description = "City trains on the five railways from Białystok, to Niewodnica, Dobrzyniewo Duże, "
                    + "Juchnowiec Dolny, Wasilków and Henrykowo, with new stations. Two ways of running them: five "
                    + "routes from Białystok (radial), or two routes through Białystok and one ending there (through), "
                    + "each timetabled at four frequencies around today's trains of one weekday. Worked out once and "
                    + "stored. This gives the railways, the rules and each frequency's trains; the routes come from "
                    + "/proposal/{solution}/{frequency}.")
    @GetMapping("/proposal")
    public CityRailProposalDto.Proposal proposal() {
        return proposal.proposal();
    }

    @Operation(summary = "One solution's routes and timetables at a frequency",
            description = "The routes of radial or through at 1tp2h, 1tph, 2tph or 3tph: their stations, lengths, run "
                    + "times, trains needed and turnarounds, and both ways' timetables beside today's Polregio and PKP "
                    + "Intercity trains. 404 when there is no such solution or frequency.")
    @GetMapping("/proposal/{solution}/{frequency}")
    public CityRailProposalDto.Variant variant(@PathVariable String solution, @PathVariable String frequency) {
        return proposal.variant(solution, frequency)
                .orElseThrow(() -> new NotFoundException("Proposal variant", solution + "/" + frequency));
    }
}
