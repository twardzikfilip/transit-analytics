package com.transitanalytics.web;

import com.transitanalytics.dto.CorridorDto;
import com.transitanalytics.service.CorridorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Corridors", description = "Stretches of stops shared by several lines")
@RestController
@RequestMapping("/api/corridors")
public class CorridorController {

    private final CorridorService corridors;

    public CorridorController(CorridorService corridors) {
        this.corridors = corridors;
    }

    @Operation(summary = "Important corridors",
            description = "From the weekday timetable: every stretch of at least 4 consecutive stops along which two "
                    + "or more lines run, as long as possible for each set of lines (so stretches can overlap), with "
                    + "both directions in one row. For each: length, lines, weekday buses running all of it, and the "
                    + "average interval and regularity (coefficient of variation of the gaps) in peak (06:30–08:30, "
                    + "15:00–17:00) and off-peak (08:30–15:00, 17:00–20:00) hours. Busiest first.")
    @GetMapping
    public CorridorDto.Report corridors() {
        return corridors.corridors();
    }
}
