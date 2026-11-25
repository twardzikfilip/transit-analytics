package com.transitanalytics.dto;

import java.time.LocalDate;
import java.util.List;

/** The railways in and around Białystok and the Polregio and PKP Intercity trains on them. */
public final class CityRailDto {

    private CityRailDto() {
    }

    /**
     * @param railways every stretch of railway in use
     * @param days     the days the departures are counted on: the next weekday, Saturday and Sunday (from today) the
     *                 timetable has
     * @param routes   empty while the timetable isn't loaded
     */
    public record Report(List<Railway> railways, List<Day> days, List<Route> routes) {
    }

    /**
     * @param ref    the railway line's number ("6"); null when it has none
     * @param spur   a spur, e.g. to a depot or factory
     * @param points [latitude, longitude] in order
     */
    public record Railway(String ref, boolean spur, List<double[]> points) {
    }

    /** @param kind weekday, saturday or sunday (a public holiday is none of them) */
    public record Day(String kind, LocalDate date) {
    }

    /**
     * The railway from Białystok to one end of the network, with all trains on it, wherever they run beyond it.
     *
     * @param id       the name of the station it ends at
     * @param stations its stations, from Białystok outwards
     * @param points   [latitude, longitude] from Białystok outwards
     * @param polregio Polregio's trains
     * @param all      Polregio's and PKP Intercity's together
     */
    public record Route(String id, List<Station> stations, List<double[]> points, Trains polregio, Trains all) {
    }

    public record Station(String name, double lat, double lon) {
    }

    /**
     * @param destinations where they start or end, the most trains first
     * @param departures   per day of {@link Report#days}
     */
    public record Trains(List<String> destinations, List<Departures> departures) {
    }

    /**
     * @param fromCity trains leaving Białystok along the route
     * @param toCity   trains to Białystok along it
     */
    public record Departures(int fromCity, int toCity) {
    }
}
