package com.transitanalytics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** How the fleet's vehicles were used in a time window, from recorded positions. */
public final class VehicleStatsDto {

    private VehicleStatsDto() {
    }

    /**
     * @param window         day, week or month: the last 24 hours, 7 days or 30 days
     * @param vehicles       vehicles that served a line in the window (of the chosen operators)
     * @param agencies       all operators with vehicles in service in the window, to choose from
     * @param mostUsed       the 10 vehicles in service the longest
     * @param leastUsed      the 10 in service the shortest (of those that served at all)
     * @param mostLines      the 10 that served the most different lines
     * @param fewestLines    the 10 that served the fewest (ties: the longest in service first)
     * @param multiLineDays  vehicles that served two or more lines on one day, newest day first
     * @param incidents      vehicles that stopped serving their brigade while it was scheduled to run, newest first
     */
    public record Report(String window, Instant from, Instant to, int vehicles, List<Agency> agencies,
                         List<Usage> mostUsed,
                         List<Usage> leastUsed, List<Usage> mostLines, List<Usage> fewestLines,
                         List<LineDay> multiLineDays, List<Incident> incidents) {
    }

    /** An operator and how many of its vehicles served a line in the window. */
    public record Agency(String name, int vehicles) {
    }

    public record Vehicle(long id, String number, String model, String agency) {
    }

    /**
     * @param hours     time in service (reporting while on a line); each report counts until the vehicle's next one,
     *                  up to a minute
     * @param days      service days on which it served a line
     * @param lines     different lines served, in line order
     * @param firstSeen first report on a line in the window
     * @param lastSeen  last report on a line in the window
     */
    public record Usage(Vehicle vehicle, double hours, int days, List<String> lines, Instant firstSeen,
                        Instant lastSeen) {
    }

    /** One vehicle's lines on one service day (03:00 to 03:00 local time), in the order it served them. */
    public record LineDay(LocalDate day, Vehicle vehicle, List<LineSpell> lines) {
    }

    /** Time on one line during a day: from the first to the last report on it. */
    public record LineSpell(String line, String brigade, Instant from, Instant to, double hours) {
    }

    /**
     * A vehicle that stopped reporting on its brigade while the timetable had the brigade in service (mid-run or in a
     * short layover, not at a break of 30 minutes or more or the end of its day), at least 15 minutes before the
     * brigade's day ended, while recording went on.
     *
     * @param kind             "switched" when the vehicle was seen later that day on another brigade, else
     *                         "disappeared"
     * @param lastSeen         its last report on the brigade
     * @param lastStop         the next stop it reported then
     * @param delaySeconds     its delay then; the schedule is compared at the time it was running to
     * @param scheduledUntil   when the brigade's block of runs (until its next break, or its day's end) was to end
     * @param scheduledEnd     when the brigade's day was to end
     * @param missedRuns       runs of that block not completed
     * @param nextLine         where it went: the line it served next (switched only)
     * @param nextBrigade      and the brigade
     * @param nextSeen         when it appeared there
     * @param replacedBy       the vehicle that took over the brigade later that day, if any
     * @param replacedAt       when that vehicle first reported on it
     */
    public record Incident(LocalDate day, Vehicle vehicle, String kind, String line, String brigade, Instant lastSeen,
                           String lastStop, Integer delaySeconds, Instant scheduledUntil, Instant scheduledEnd,
                           int missedRuns, String nextLine, String nextBrigade, Instant nextSeen, Vehicle replacedBy,
                           Instant replacedAt) {
    }
}
