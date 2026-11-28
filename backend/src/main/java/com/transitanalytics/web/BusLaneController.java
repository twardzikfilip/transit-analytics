package com.transitanalytics.web;

import com.transitanalytics.buslanes.BusLaneData;
import com.transitanalytics.buslanes.BusLaneProperties;
import com.transitanalytics.buslanes.BusLaneReportService;
import com.transitanalytics.buslanes.BusLanes;
import com.transitanalytics.common.ReportWindows;
import com.transitanalytics.dto.BusLaneDto;
import com.transitanalytics.service.ExternalServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static com.transitanalytics.timetable.TimetableMath.round;

@Tag(name = "Bus lanes", description = "Bus lanes from OpenStreetMap, and speeds and delays on them")
@RestController
@RequestMapping("/api/bus-lanes")
public class BusLaneController {

    private final BusLanes lanes;
    private final BusLaneReportService reports;
    private final ReportWindows windows;

    public BusLaneController(BusLanes lanes, BusLaneReportService reports, ReportWindows windows) {
        this.lanes = lanes;
        this.reports = reports;
        this.windows = windows;
    }

    @Operation(summary = "The city's bus lanes",
            description = "Every street section with a lane for buses (OpenStreetMap, © OpenStreetMap contributors, "
                    + "ODbL), with how many lanes run each way, and the sections grouped by street and direction "
                    + "of travel; the length of bus lane and the official figure for comparison.")
    @GetMapping
    public BusLaneDto.Lanes lanes() {
        return toDto(lanes.current());
    }

    @Operation(summary = "Fetch the bus lanes again from OpenStreetMap (admin)",
            description = "Asks the Overpass servers in map/bus-lanes.yml in turn; saves the answer to the "
                    + "configured file and uses it from then on. 502 when no server answers.")
    @PostMapping("/refresh")
    public BusLaneDto.Lanes refresh() {
        try {
            return toDto(lanes.refresh());
        } catch (IllegalStateException ex) {
            throw new ExternalServiceException("Overpass", ex);
        }
    }

    @Operation(summary = "Speeds and delays on bus lanes and elsewhere",
            description = "In a time window, on the timetable's routes, inside the city: every pair of consecutive "
                    + "position reports of a bus (5-90 s apart) counts as on a bus lane when its middle is within "
                    + "15 m of one and the bus heads its way. Speeds include stops; delay change is the delay gained "
                    + "per km. Layovers at termini are left out. Peak and off-peak are weekday 06:30-08:30 and "
                    + "15:00-17:00, and 08:30-15:00 and 17:00-20:00. Cached for 2 minutes.")
    @GetMapping("/report")
    public BusLaneDto.Report report(
            @Parameter(description = "Start (ISO-8601); the first recording when absent") @RequestParam(required = false) Instant from,
            @Parameter(description = "End, exclusive (ISO-8601); now when absent") @RequestParam(required = false) Instant to) {
        return reports.report(windows.resolve(from, to));
    }

    private BusLaneDto.Lanes toDto(BusLanes.Snapshot snapshot) {
        BusLaneData data = snapshot.data();
        BusLaneProperties.Official official = lanes.properties().official();
        return new BusLaneDto.Lanes(data.source(), data.fetchedAt(), round(data.laneKm(), 2), data.lanes().size(),
                official == null ? null : new BusLaneDto.Official(official.km(), official.date(), official.source(),
                        official.url()),
                java.util.stream.IntStream.range(0, data.lanes().size()).mapToObj(i -> {
                    var l = data.lanes().get(i);
                    return new BusLaneDto.Lane(l.id(), l.name(), l.forward(), l.backward(), snapshot.bothWays()[i],
                            l.points());
                }).toList(),
                snapshot.stretches().all().stream().map(s -> new BusLaneDto.Stretch(s.id(), s.name(), s.heading(),
                        round(s.meters() / 1000, 2), s.paths())).toList());
    }
}
