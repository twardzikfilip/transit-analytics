package com.transitanalytics.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * City Rail proposal no. 1: city trains on the five railways from Białystok, shortened to Klepacze, Fasty, Lewickie,
 * Wasilków and Kuriany, with new stations, and two ways of running them, each timetabled around today's trains.
 */
public final class CityRailProposalDto {

    private CityRailProposalDto() {
    }

    /**
     * @param day        the weekday whose trains the timetables keep clear of; null while the trains' timetable isn't
     *                   loaded (then there are no solutions)
     * @param rules      what every timetable keeps to
     * @param corridors  the railways, from Białystok outwards
     * @param solutions  the ways of running lines on them
     */
    public record Proposal(LocalDate day, Rules rules, List<Corridor> corridors, List<Solution> solutions) {
    }

    /**
     * @param headwayMinutes     at least this long after or before any other train going the same way on the same
     *                           railway, at every station
     * @param turnaroundMinutes  at least this long at an end before a train goes back
     * @param intervalMinutes    at most this long between two trains of a line, each way
     * @param firstDeparture     "HH:MM": the first trains leave their ends by then...
     * @param lastDeparture      "HH:MM": ...and the last ones no later than this
     * @param newStationDwell    seconds a train stands at a new station: as long as Polregio's at Białystok Starosielce
     * @param hubDwell           seconds a train running through Białystok stands there
     * @param trainsConsidered   today's trains (Polregio and PKP Intercity) on the railways, which the timetables keep
     *                           clear of
     */
    public record Rules(int headwayMinutes, int turnaroundMinutes, int intervalMinutes, String firstDeparture,
                        String lastDeparture, int newStationDwell, int hubDwell, int trainsConsidered) {
    }

    /**
     * @param id        its end, in lower case ("klepacze")
     * @param ref       the railway line's number
     * @param served    whether any train runs on it today
     * @param stations  from Białystok outwards; the last is the end
     * @param points    [latitude, longitude] from Białystok to the end
     */
    public record Corridor(String id, String ref, boolean served, List<Station> stations, List<double[]> points) {
    }

    /**
     * @param kind  served (trains call there today), unserved (a station no train calls at today) or new
     * @param km    along the railway from Białystok
     */
    public record Station(String name, String kind, double lat, double lon, double km) {
    }

    /** @param id radial (five lines from Białystok) or through (lines running through Białystok) */
    public record Solution(String id, List<Line> lines) {
    }

    /**
     * One line, both ways.
     *
     * @param id                the line's number ("S1")
     * @param stations          from one end to the other, with the distance along the line from the first
     * @param points            [latitude, longitude] from the first station to the last
     * @param km                its length
     * @param spacingKm         the average distance between two stations
     * @param runMinutes        from the first station to the last
     * @param runMinutesBack    from the last back to the first
     * @param trainsPerDay      each way (the fewer of the two)
     * @param maxIntervalMinutes the longest wait between two trains, either way
     * @param vehicles          trains needed to run it, each turning at least the turnaround time at an end
     * @param minTurnaround     the shortest time a train stands at an end before going back, in minutes
     * @param closestMinutes    the nearest any of its trains comes to another train on the same railway and way, in
     *                          minutes; null when it shares its railways with none
     * @param conflicts         trains of its timetable that come closer than the headway (0 when it keeps to it)
     * @param out               trains from the first station to the last
     * @param back              trains from the last station to the first
     */
    public record Line(String id, List<LineStation> stations, List<double[]> points, double km, double spacingKm,
                       int runMinutes, int runMinutesBack, int trainsPerDay, int maxIntervalMinutes, int vehicles,
                       int minTurnaround, Integer closestMinutes, int conflicts, List<Trip> out, List<Trip> back) {
    }

    /** @param km from the line's first station */
    public record LineStation(String name, String kind, double km) {
    }

    /**
     * @param times "HH:MM" per station of its way: the departure, at the last its arrival
     * @param conflict whether it comes closer than the headway to another train
     */
    public record Trip(List<String> times, boolean conflict) {
    }
}
