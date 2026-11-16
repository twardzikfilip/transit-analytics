package com.transitanalytics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A brigade's (vehicle duty's) runs on one service day, from the timetable. */
public final class BrigadeDto {

    private BrigadeDto() {
    }

    /**
     * @param brigade    e.g. 103-05, as live feeds name it
     * @param serviceDay the day the runs belong to; runs after midnight belong to the day before
     * @param runs       in departure order; empty when the brigade doesn't run that day or is not in the timetable
     */
    public record Schedule(String brigade, LocalDate serviceDay, List<Run> runs) {
    }

    /** One trip of the brigade, from its first stop to its last. */
    public record Run(String line, String headsign, String from, String to, Instant departure, Instant arrival) {
    }
}
