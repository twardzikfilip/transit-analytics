package com.transitanalytics.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * City Rail proposal no. 1: city trains on the five railways from Białystok, shortened to Niewodnica, Dobrzyniewo Duże,
 * Lewickie, Wasilków and Henrykowo, with new stations, and two ways of running them, each timetabled at four
 * frequencies around the trains of one weekday. Worked out once and stored: an analysis of the timetable as it is.
 */
public final class CityRailProposalDto {

    private CityRailProposalDto() {
    }

    /**
     * @param day        the weekday whose trains the timetables keep clear of; null while the trains' timetable isn't
     *                   loaded (then there are no solutions)
     * @param rules      what every timetable keeps to
     * @param corridors  the railways, from Białystok outwards
     * @param solutions  the ways of running routes on them, with the trains each frequency needs (its routes: {@link
     *                   Variant}, asked for one at a time)
     */
    public record Proposal(LocalDate day, Rules rules, List<Corridor> corridors, List<Solution> solutions) {
    }

    /**
     * @param headwayMinutes     at least this long after or before any other train going the same way on the same
     *                           railway, at every station
     * @param turnaroundMinutes  at least this long at an end before a train goes back
     * @param firstDeparture     "HH:MM": the first trains leave their ends by then...
     * @param lastDeparture      "HH:MM": ...and the last ones no later than this
     * @param newStationDwell    seconds a train stands at a new station: as long as Polregio's at Białystok Starosielce
     * @param hubDwell           seconds a train running through Białystok stands there
     * @param trainsConsidered   today's trains (Polregio and PKP Intercity) on the railways, which the timetables keep
     *                           clear of
     */
    public record Rules(int headwayMinutes, int turnaroundMinutes, String firstDeparture,
                        String lastDeparture, int newStationDwell, int hubDwell, int trainsConsidered) {
    }

    /**
     * @param id        its end, in lower case ("niewodnica")
     * @param ref       the railway line's number
     * @param served    whether any train runs on it today
     * @param stations  from Białystok outwards; the last is the end
     * @param points    [latitude, longitude] from Białystok to the end
     */
    public record Corridor(String id, String ref, boolean served, List<Station> stations, List<double[]> points) {
    }

    /**
     * @param kind  served (trains call there today), unserved (a station no train calls at today), passed (one trains
     *              call at today that the city trains run through) or new
     * @param km    along the railway from Białystok
     */
    public record Station(String name, String kind, double lat, double lon, double km) {
    }

    /**
     * @param id       radial (five routes from Białystok) or through (routes running through Białystok)
     * @param variants one per frequency, least frequent first
     */
    public record Solution(String id, List<Choice> variants) {
    }

    /** A frequency of a solution, and the trains all its routes need together. */
    public record Choice(String frequency, int vehicles) {
    }

    /**
     * The solution's routes at one frequency.
     *
     * @param frequency      1tp2h, 1tph, 2tph or 3tph: at least one train every two hours, or 1, 2 or 3 an hour,
     *                       each way
     * @param vehicles       trains all its routes need together
     */
    public record Variant(String frequency, int vehicles, List<Route> routes) {
    }

    /**
     * One route, both ways.
     *
     * @param id                the route's number ("S1")
     * @param stations          from one end to the other, with the distance along the route from the first (those
     *                          passed without stopping too)
     * @param points            [latitude, longitude] from the first station to the last
     * @param km                its length
     * @param spacingKm         the average distance between two stations it calls at
     * @param runMinutes        from the first station to the last
     * @param runMinutesBack    from the last back to the first
     * @param trainsPerDay      each way (the fewer of the two)
     * @param maxIntervalMinutes the longest wait between two trains, either way
     * @param vehicles          trains needed to run it, each turning at least the turnaround time at an end
     * @param minTurnaround     the shortest time a train stands at an end before going back, in minutes
     * @param maxTurnaround     the longest, in minutes
     * @param closestMinutes    the nearest any of its trains comes to another train on the same railway and way, in
     *                          minutes; null when it shares its railways with none
     * @param conflicts         trains of its timetable that come closer than the headway (0 when it keeps to it)
     * @param out               trains from the first station to the last: its own, and today's trains on its way
     *                          (each on the part of it they run on), in order
     * @param back              trains from the last station to the first, the same way
     */
    public record Route(String id, List<RouteStation> stations, List<double[]> points, double km, double spacingKm,
                       int runMinutes, int runMinutesBack, int trainsPerDay, int maxIntervalMinutes, int vehicles,
                       int minTurnaround, int maxTurnaround, Integer closestMinutes, int conflicts, List<Trip> out, List<Trip> back) {
    }

    /** @param km from the route's first station */
    public record RouteStation(String name, String kind, double km) {
    }

    /**
     * @param kind     city (the route's own), PR (Polregio) or IC (PKP Intercity)
     * @param label    the route's number, or the train's number and name ("16132/3 Dąbrowska")
     * @param times    per station of the way: "HH:MM" (the departure, at its last its arrival), "|" where it passes
     *                 without stopping, null where it isn't on the route
     * @param conflict whether it comes closer than the headway to another train (the route's own only)
     */
    public record Trip(String kind, String label, List<String> times, boolean conflict) {
    }
}
