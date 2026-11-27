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

    /**
     * Train against bus between the stations in the city: the fastest Polregio journey and the fastest off-peak bus
     * journey from the bus stops nearest each station, each with up to {@code maxChanges} changes.
     *
     * @param day        the weekday of the timetables compared; null while the trains' timetable isn't loaded
     * @param offPeak    the times buses leave in ("HH:MM-HH:MM"); trains leave at any time of the day
     * @param maxChanges at most this many changes a journey
     * @param stations   the stations in the city, with their bus stops
     * @param pairs      every station to every other
     */
    public record Comparison(LocalDate day, List<String> offPeak, int maxChanges, List<ComparedStation> stations,
                             List<Pair> pairs) {
    }

    /** A station in the city and the bus stops nearest it, which bus journeys start and end at. */
    public record ComparedStation(String name, List<NearStop> stops) {
    }

    /** @param meters straight from the station */
    public record NearStop(String name, int meters) {
    }

    /**
     * @param train the fastest by train; null when there is none
     * @param bus   the fastest by bus off-peak; null when there is none
     */
    public record Pair(String from, String to, Journey train, Journey bus) {
    }

    /**
     * @param minutes   from the first departure to the last arrival, changes included
     * @param departure "HH:MM" of the first departure
     * @param legs      one per ride; a change between two
     */
    public record Journey(int minutes, String departure, String arrival, List<Leg> legs) {
    }

    /** @param line the bus line, or the train's number */
    public record Leg(String line, String from, String to, String departure, String arrival) {
    }
}
