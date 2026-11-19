package com.transitanalytics.web;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Live data from zbiorkom.live: transit-recorder's latest snapshot, or the feed itself when the recorder is down. */
@Tag(name = "Live data: Białystok", description = "Live data from zbiorkom.live")
@RestController
@RequestMapping("/api/zbiorkom/bialystok")
public class ZbiorkomController {

    private final ZbiorkomBialystokService bialystok;

    public ZbiorkomController(ZbiorkomBialystokService bialystok) {
        this.bialystok = bialystok;
    }

    @Operation(summary = "Live vehicle positions",
            description = "transit-recorder's latest snapshot (it polls every 10 seconds); straight from zbiorkom.live "
                    + "when the recorder doesn't answer or is behind. Vehicles seen for the first time are added to the "
                    + "vehicle table.")
    @ApiResponse(responseCode = "200", description = "Current positions")
    @ApiResponse(responseCode = "502", description = "The recorder is down and zbiorkom.live failed or timed out")
    @GetMapping("/positions")
    public List<LivePositionDto> positions() {
        return bialystok.positions();
    }
}
