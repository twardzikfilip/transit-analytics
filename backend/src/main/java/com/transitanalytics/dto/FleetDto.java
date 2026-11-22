package com.transitanalytics.dto;

import com.transitanalytics.domain.VehicleClass;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The fleet: how its buses were used in a time window, and one bus in detail, from recorded positions. */
public final class FleetDto {

    private FleetDto() {
    }

    public record Vehicle(long id, String number, String model, String agency, VehicleClass vehicleClass) {
    }

    /**
     * @param inUse      buses of the chosen operators that served a line in the window
     * @param fleet      buses of the chosen operators: every bus ever seen on the live feed
     * @param agencies   every operator of the fleet, with its number of buses, to choose from
     * @param models     buses per model, in use and in the fleet, the most in use first
     * @param classes    buses per size class ("unknown" for models without one), smallest to largest
     * @param makes      buses per make (the model name's first word), the most in use first
     * @param mostUsed   the 10 in service the longest
     * @param leastUsed  the 10 in service the shortest, of those that served a line at all
     * @param usage      every bus of the chosen operators (and any other vehicle that served a line), the longest in
     *                   service first; buses not used have 0 hours
     * @param breakdowns buses that broke down in the window, the latest first
     */
    public record Report(Instant from, Instant to, int inUse, int fleet, List<Agency> agencies,
                         List<ModelCount> models, List<ModelCount> classes, List<ModelCount> makes,
                         List<Usage> mostUsed, List<Usage> leastUsed, List<Usage> usage,
                         List<Breakdown> breakdowns) {
    }

    /** An operator and how many buses of the fleet are its. */
    public record Agency(String name, int vehicles) {
    }

    /**
     * A model ("unknown" when the feed doesn't say) and how many of its buses were in use, of how many; also used for
     * size classes and makes.
     */
    public record ModelCount(String model, int inUse, int fleet) {
    }

    /**
     * @param hours time in service: each report on a line counts until the bus's next one, up to a minute
     * @param days  service days on which it served a line
     * @param lines the lines it served, in line order
     */
    public record Usage(Vehicle vehicle, double hours, int days, List<String> lines) {
    }

    /**
     * A bus that broke down: replaced midway through a trip and not used again that day, as on Abnormal trips.
     *
     * @param at        when it stopped for good on the trip
     * @param stop      the stop it was heading to then
     * @param returned  its first report on a line after that day, null if it hasn't served a line since
     * @param daysOut   days from breaking down until it returned, or until now when it hasn't
     */
    public record Breakdown(Vehicle vehicle, Instant at, String line, String brigade, String headsign, String stop,
                            Instant returned, double daysOut) {
    }

    /**
     * One bus in a window.
     *
     * @param hours     time in service in the window
     * @param lastSeen  its last report on a line before the end of the window, null if never
     * @param days      each service day it served a line on, in order
     * @param incidents abnormal trips it was involved in: it broke down on one, or missed one of its brigade's trips
     */
    public record VehicleDetail(Vehicle vehicle, Instant from, Instant to, double hours, List<String> lines,
                                Instant lastSeen, List<Day> days, List<Incident> incidents) {
    }

    /**
     * A service day of a bus.
     *
     * @param start what {@code from}/{@code to} of its runs and breaks count from: midnight before the service day's
     *              03:00 start (so a run at 01:10 after midnight is at 25:10)
     * @param runs  its runs: consecutive reports on one line, brigade and headsign
     * @param breaks gaps of 30 minutes or more between its runs
     */
    public record Day(LocalDate day, Instant start, double hours, List<Run> runs, List<Break> breaks) {
    }

    /** A run, from its first to its last report, in seconds after the day's start. */
    public record Run(int from, int to, String line, String brigade, String headsign) {
    }

    public record Break(int from, int to) {
    }

    /**
     * An abnormal trip a bus was involved in.
     *
     * @param kind     vehicle-changed (it broke down: replaced and not used again that day) or missed (its brigade's
     *                 trip not run while it ran the brigade's other trips)
     * @param story    on a breakdown, what happened step by step, in time order; empty otherwise
     * @param timeline on a breakdown, what each bus (it and its replacement) did when
     * @param paths    on a breakdown, where each of those buses went over the time line, for a map
     */
    public record Incident(String kind, LocalDate day, String line, String brigade, String headsign,
                           Instant scheduledDeparture, List<TripAnomalyDto.Phrase> note,
                           List<TripAnomalyDto.Step> story,
                           List<TripAnomalyDto.Span> timeline, List<Path> paths) {

        public Incident withPaths(List<Path> paths) {
            return new Incident(kind, day, line, brigade, headsign, scheduledDeparture, note, story, timeline, paths);
        }
    }

    /** A bus's recorded positions in order, {latitude, longitude}. */
    public record Path(String vehicle, List<double[]> points) {
    }
}
