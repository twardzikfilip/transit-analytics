package com.transitanalytics.dto;

import com.transitanalytics.domain.RouteCapacity;
import com.transitanalytics.domain.RouteGeometry;
import com.transitanalytics.domain.RouteSubtype;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One route in detail: its variants and timetable figures, and its performance measured from vehicle positions. */
public final class RouteDetailDto {

    private RouteDetailDto() {
    }

    /**
     * A route to pick from. {@code route} is the route's GTFS id, used in the detail URLs; for a route only seen on the
     * live feed (another operator's, not in the timetable) it is the route's name, and {@code id} is null.
     *
     * @param geometry      how it runs relative to the centre; null when not classified (other operators' routes)
     * @param capacityClass how much it carries; null when not classified
     * @param operatingAgency the company operating it for BKM (KPKM, KPK, KZK); null when not known
     */
    public record RouteRef(Long id, String route, String name, RouteSubtype subtype, RouteGeometry geometry,
                          RouteCapacity capacityClass, String operatingAgency) {
    }

    /**
     * Timetable figures for the regular weekday service.
     *
     * @param zone2      per variant reaching fare zone 2 (depot runs aside); empty when the route stays in the city
     * @param endStops   where buses turn round in service (the termini); stops closer than 500 m count as one
     * @param depotStops where depot runs start or end, away from the end stops; grouped the same way
     * @param depotRuns  runs from the depot into service (pull-out) or back (pull-in), by time
     * @param operator   who runs the route: the organiser and the operating company with its depot; null when not
     *                   known
     * @param depots     every bus depot
     * @param depotLinks where the route's buses enter and leave service, each with the way from or to the depot
     * @param busLanes   how much of each direction's main route runs on bus lanes
     * @param recorded   for a route without a timetable: where its buses went, from their recorded positions; null
     *                   for a route in the timetable
     * @param brigadesKnown whether the timetable says which brigade runs each trip (another operator's may not:
     *                   then there are no brigades or depot runs to show)
     * @param id         the route's id (for its trips), null for a route not in the timetable
     * @param url        the route's timetable on the operator's website, if any
     */
    public record Detail(Long id, String route, String name, RouteSubtype subtype, RouteGeometry geometry,
                         RouteCapacity capacityClass, String serviceId, String url,
                         Base base, List<Variant> variants, List<Zone2> zone2, List<Brigade> brigades,
                         List<EndStop> endStops, List<EndStop> depotStops, List<DepotRun> depotRuns,
                         Operator operator, List<Depot> depots, List<DepotLink> depotLinks,
                         List<BusLaneDto.RouteShare> busLanes, Recorded recorded, boolean brigadesKnown) {
    }

    /**
     * Where a route's buses went, from all their recorded positions: its variants, the most driven first.
     *
     * @param first when the first run began; null when nothing usable was recorded
     * @param runs  runs found (a bus's reports in a row towards one destination)
     */
    public record Recorded(Instant first, Instant last, int runs, List<RecordedVariant> variants) {
    }

    /**
     * A way the route's buses went: towards one destination along the same streets.
     *
     * @param headsign where the buses were going
     * @param from     the stop they were first heading to, most often; null when not reported
     * @param to       the stop they were last heading to, most often
     * @param runs     how many times it was driven
     * @param km       length of the path
     * @param lastSeen when it was last driven
     * @param path     [lat, lon] in the direction of travel
     */
    public record RecordedVariant(int id, String headsign, String from, String to, int runs, double km,
                                  Instant lastSeen, List<double[]> path) {
    }

    /**
     * A terminus, or a depot-run stop: stops less than 500 m apart are one place, named after the stop where runs end
     * there most often.
     *
     * @param alsoNames the other stops of the place, e.g. the departure platform
     */
    public record EndStop(String name, List<String> alsoNames) {
    }

    /**
     * Who runs a route.
     *
     * @param organiser    the organiser of public transport (BKM)
     * @param code         the operating company: KPKM, KPK or KZK
     * @param depot        its depot's name, and {@code depotAddress} its address
     */
    public record Operator(String organiser, String code, String name, String website, String depot,
                           String depotAddress) {
    }

    /**
     * A bus depot.
     *
     * @param code     the company whose depot it is
     * @param outlines its area: closed rings of {@code [lat, lon]}
     */
    public record Depot(String code, String name, String address, List<List<double[]>> outlines) {
    }

    /**
     * Where the route's buses enter or leave service, with the way between there and the operator's depot (the
     * timetable doesn't have it).
     *
     * @param kind     "pull_out" (the first stop of brigades' first trips of the day) or "pull_in" (the last stop of
     *                 their last trips)
     * @param depot    the depot's code
     * @param brigades how many brigades do so there
     * @param path     the way from the depot to the stop (pull-out) or back (pull-in), {@code [lat, lon]}
     * @param routed   whether {@code path} follows the streets (the shortest drive), else a straight line
     */
    public record DepotLink(String kind, String stop, double lat, double lon, String depot, int brigades,
                            List<double[]> path, boolean routed) {
    }

    /**
     * A run between the depot area and the route, at the start of a brigade's day or around a break.
     *
     * @param kind "pull_out" (from the depot into service) or "pull_in" (back to the depot)
     */
    public record DepotRun(String brigade, int from, int to, String fromStop, String toStop, int direction,
                           String headsign, String kind, Double km) {
    }

    /**
     * The base route: the most frequent variant of each direction.
     *
     * @param lengthKm    average of the two directions' lengths
     * @param minSpeedKmh average speed of the slowest scheduled trip (distance / travel time)
     * @param maxSpeedKmh average speed of the fastest scheduled trip
     */
    public record Base(Double lengthKm, Integer shortestMin, Integer longestMin, Double minSpeedKmh,
                       Double maxSpeedKmh) {
    }

    /**
     * One way the route runs: a direction and route shape.
     *
     * @param kind        "main" (the base route of its direction), "longer" or "shorter" than it, or "depot" when
     *                    most of its runs go to or from the depot
     * @param minSpeedKmh average speed of its slowest scheduled trip (distance / travel time)
     * @param maxSpeedKmh average speed of its fastest scheduled trip
     * @param points      the route shape as [latitude, longitude] pairs
     */
    public record Variant(int direction, String shapeId, String headsign, String kind, double km, int tripsPerDay,
                          int shortestMin, int longestMin, Double minSpeedKmh, Double maxSpeedKmh, String firstStop,
                          String lastStop, List<double[]> points, List<StopPoint> stops) {
    }

    /** @param km distance along the route from its first stop */
    public record StopPoint(String name, Double lat, Double lon, double km, String zone) {
    }

    /**
     * The part of a direction's base route outside the city (fare zone 2).
     *
     * @param km distance in zone 2: segments between two zone-2 stops, plus half of each segment crossing the border
     */
    public record Zone2(int direction, String headsign, String kind, int stops, int totalStops, double km,
                        double totalKm) {
    }

    /**
     * One bus's duty for the day (brigade). Times are seconds after midnight and can pass 24:00.
     *
     * @param start first departure: the bus leaves the depot and enters service
     * @param end   last arrival: the bus leaves service and returns to the depot
     */
    public record Brigade(String name, int start, int end, List<Run> runs, List<Break> breaks) {
    }

    /**
     * @param kind  the variant: "main", "longer", "shorter" or "depot"
     * @param depot "pull_out" or "pull_in" when this run goes from or to the depot, else null
     * @param trip  its trip's id in the timetable (for Trips: what ran on it)
     */
    public record Run(int from, int to, int direction, String headsign, String kind, String depot, String trip) {
    }

    /** A gap between two runs of at least {@code LineDetailService.BREAK_SECONDS}. */
    public record Break(int from, int to) {
    }

    /**
     * Measured from recorded vehicle positions on weekdays (Mon–Fri). Hours are local (Europe/Warsaw) and run past
     * midnight as 24, 25, … so a service day is continuous.
     *
     * @param fullRuns    runs recorded from the first to the last stop, of the base route and longer variants
     * @param travelTimes travel time and speed of full runs by departure hour: the base route first, then each
     *                    longer variant
     * @param delays      average delay per 15 minutes of the day
     * @param vehicles    which buses served the route (all recorded days, weekends too)
     */
    public record Performance(LocalDate firstDay, LocalDate lastDay, int weekdays, int fullRuns,
                              List<TravelTimes> travelTimes, List<DelaySlot> delays, Vehicles vehicles) {
    }

    /**
     * Full runs of one route pattern (the base route, or a longer variant, both directions).
     *
     * @param kind      "main" or "longer"
     * @param label     e.g. "Base route" or "Kolejowa/Dworzec PKP ⇄ Kolonia Koplany"
     * @param durations travel time in minutes, by departure hour
     * @param speeds    average speed in km/h, by departure hour
     */
    public record TravelTimes(String kind, String label, double km, int runs, Integer scheduledShortestMin,
                              Integer scheduledLongestMin, Double scheduledMinSpeedKmh, Double scheduledMaxSpeedKmh,
                              List<HourStat> durations, List<HourStat> speeds) {
    }

    /**
     * The buses that served the route.
     *
     * @param days     service days with any recorded position of the route
     * @param switches brigades that changed bus during a day
     */
    public record Vehicles(int days, List<VehicleUse> vehicles, List<BrigadeVehicles> brigades,
                           List<VehicleSwitch> switches) {
    }

    /** @param days service days the bus ran on this route */
    public record VehicleUse(String number, String model, int days, List<String> brigades, Instant lastSeen) {
    }

    /** @param vehicles different buses seen on this brigade */
    public record BrigadeVehicles(String brigade, String mostUsed, int mostUsedDays, int days, int vehicles) {
    }

    /** One brigade's day with more than one bus, in the order they served it. */
    public record VehicleSwitch(LocalDate day, String brigade, List<VehicleSpell> spells) {
    }

    public record VehicleSpell(String number, Instant from, Instant to) {
    }

    public record HourStat(int hour, int runs, double min, double max, double avg, double median) {
    }

    /** @param minute start of the 15-minute slot, minutes after midnight (can pass 1440) */
    public record DelaySlot(int minute, double avgDelaySeconds, long samples) {
    }

}
