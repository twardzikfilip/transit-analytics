package com.transitanalytics.web;

import com.transitanalytics.common.DaySummaries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Tag(name = "Day summaries", description = "Reports' stored days")
@RestController
@RequestMapping("/api/admin/day-summaries")
public class DaySummaryController {

    private static final int MAX_DAYS = 3660;

    private final DaySummaries summaries;

    public DaySummaryController(DaySummaries summaries) {
        this.summaries = summaries;
    }

    /** How many stored days were dropped. */
    public record Deleted(int deleted) {
    }

    @Operation(summary = "Drop stored days", description = "Drops reports' stored days from `from` to `to` (service "
            + "days, both included), of one report or of all, so they are worked out again. The recorded positions "
            + "aren't touched. Admin only.")
    @DeleteMapping
    public Deleted delete(@RequestParam(required = false) String report, @RequestParam LocalDate from,
                          @RequestParam LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ResponseStatusException(BAD_REQUEST, "from must be before to, at most ten years");
        }
        return new Deleted(summaries.delete(report, from, to));
    }
}
