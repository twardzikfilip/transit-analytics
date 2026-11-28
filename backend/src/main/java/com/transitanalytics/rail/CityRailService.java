package com.transitanalytics.rail;

import com.transitanalytics.data.RailTrains;
import com.transitanalytics.data.RailTrains.Station;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.geo.PlaceData;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.gtfs.PublicHolidays;
import com.transitanalytics.timetable.ServiceDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The railways in and around Białystok and the Polregio and PKP Intercity trains on them, up to the ends of the network
 * ({@link CityRailProperties}). The timetable doesn't name routes there, so the routes are worked out here: one to each
 * end, with every train whose track leads from Białystok past it, wherever it runs beyond and whether it calls there.
 */
@Service
@EnableConfigurationProperties(CityRailProperties.class)
public class CityRailService {

    private static final Logger log = LoggerFactory.getLogger(CityRailService.class);

    /** Polregio's agency id in the feed. */
    private static final String POLREGIO = "PR";
    /** A track this close to a station is the station's. */
    static final double AT_STATION_METERS = 300;

    private final RailTrains railTrains;
    private final Clock clock;
    private final CityRailProperties network;
    private final List<CityRailDto.Railway> railways =
            List.of(PlaceData.json("osm/railways.json", CityRailDto.Railway[].class));
    private volatile Cached cached;

    private record Cached(LocalDate day, CityRailDto.Report report) {
    }

    public CityRailService(RailTrains railTrains, Clock clock, CityRailProperties network) {
        this.railTrains = railTrains;
        this.clock = clock;
        this.network = network;
    }

    @EventListener
    public void afterImport(GtfsImported imported) {
        if (TimetableFeed.RAIL.equals(imported.feed())) {
            cached = null;
        }
    }

    /** The report for today, kept until the next day or import. */
    public CityRailDto.Report report() {
        LocalDate today = ServiceDay.serviceDay(clock.instant());
        Cached current = cached;
        if (current == null || !current.day().equals(today)) {
            current = new Cached(today, report(today));
            cached = current;
        }
        return current.report();
    }

    private CityRailDto.Report report(LocalDate today) {
        Map<String, Set<LocalDate>> services = railTrains.serviceDates(today);
        List<CityRailDto.Day> days = days(services.values().stream().flatMap(Set::stream).distinct().sorted().toList());
        return new CityRailDto.Report(railways, days, routes(services, days));
    }

    // --- the days ------------------------------------------------------------------------------------------------


    /** The first weekday, Saturday and Sunday of the dates that are no public holiday. */
    static List<CityRailDto.Day> days(List<LocalDate> dates) {
        Map<String, Predicate<DayOfWeek>> kinds = new LinkedHashMap<>();
        kinds.put("weekday", d -> d.getValue() <= DayOfWeek.FRIDAY.getValue());
        kinds.put("saturday", d -> d == DayOfWeek.SATURDAY);
        kinds.put("sunday", d -> d == DayOfWeek.SUNDAY);
        List<CityRailDto.Day> days = new ArrayList<>();
        kinds.forEach((kind, is) -> dates.stream()
                .filter(d -> is.test(d.getDayOfWeek()) && !PublicHolidays.isHoliday(d)).findFirst()
                .ifPresent(d -> days.add(new CityRailDto.Day(kind, d))));
        return days;
    }

    // --- the routes ----------------------------------------------------------------------------------------------


    /** A train's calls and track. */
    private record Train(String agency, String service, String shape, List<double[]> track, List<Station> calls) {
    }

    /** The part of a train's run on one side of Białystok, seen from there outwards: its calls and its track. */
    private record Leg(Train train, boolean fromCity, List<Station> calls, List<double[]> track) {

        Station last() {
            return calls.get(calls.size() - 1);
        }
    }

    /** A route being put together: its end, its track up to there, and its trains. */
    private record Route(Station end, List<double[]> points, List<Leg> legs) {
    }

    private List<CityRailDto.Route> routes(Map<String, Set<LocalDate>> services, List<CityRailDto.Day> days) {
        Map<String, Route> routes = new LinkedHashMap<>();
        List<Leg> shorter = new ArrayList<>();
        List<Station> ends = railTrains.stations(network.ends());
        for (Leg leg : legs()) {
            Station end = ends.stream().filter(e -> nearestMeters(leg.track(), e) < AT_STATION_METERS).findFirst()
                    .orElse(null);
            if (end == null) {
                shorter.add(leg);
                continue;
            }
            routes.computeIfAbsent(end.id(), k -> new Route(end,
                    leg.track().subList(0, nearest(leg.track(), end) + 1), new ArrayList<>())).legs().add(leg);
        }
        // Trains that turn back before the end of the network.
        for (Leg leg : shorter) {
            routes.values().stream().filter(l -> onTrack(l.points(), leg.last())).findFirst()
                    .ifPresentOrElse(l -> l.legs().add(leg),
                            () -> log.warn("City rail: a train to {} is on no route", leg.last().name()));
        }
        return routes.values().stream().map(l -> route(l, services, days))
                .sorted(Comparator.comparing(CityRailDto.Route::id)).toList();
    }

    private List<Leg> legs() {
        Map<String, List<double[]>> tracks = new HashMap<>();
        List<Leg> legs = new ArrayList<>();
        for (Train train : trains()) {
            List<double[]> shape = train.track().isEmpty() ? null : train.track();
            int at = train.calls().stream().map(Station::id).toList().indexOf(network.hub());
            if (shape == null || at < 0) {
                continue;
            }
            Station hub = train.calls().get(at);
            int n = train.calls().size();
            if (at < n - 1) {
                legs.add(new Leg(train, true, train.calls().subList(at, n),
                        tracks.computeIfAbsent(train.shape() + " out", k -> from(hub, shape))));
            }
            if (at > 0) {
                legs.add(new Leg(train, false, reversed(train.calls().subList(0, at + 1)),
                        tracks.computeIfAbsent(train.shape() + " in", k -> from(hub, reversed(shape)))));
            }
        }
        return legs;
    }

    private static CityRailDto.Route route(Route route, Map<String, Set<LocalDate>> services,
                                           List<CityRailDto.Day> days) {
        // Fast trains pass some stations: those of all trains, in order along the track.
        Map<String, Station> calls = new LinkedHashMap<>();
        route.legs().forEach(l -> l.calls().stream().filter(c -> onTrack(route.points(), c))
                .forEach(c -> calls.putIfAbsent(c.id(), c)));
        calls.putIfAbsent(route.end().id(), route.end());
        List<CityRailDto.Station> stations = calls.values().stream()
                .sorted(Comparator.comparingInt(c -> nearest(route.points(), c)))
                .map(c -> new CityRailDto.Station(c.name(), c.lat(), c.lon())).toList();
        return new CityRailDto.Route(route.end().name(), stations, route.points(),
                trains(route.legs().stream().filter(l -> POLREGIO.equals(l.train().agency())).toList(), services, days),
                trains(route.legs(), services, days));
    }

    private static CityRailDto.Trains trains(List<Leg> legs, Map<String, Set<LocalDate>> services,
                                             List<CityRailDto.Day> days) {
        Map<String, Long> ends = legs.stream()
                .collect(Collectors.groupingBy(l -> l.last().name(), Collectors.counting()));
        List<String> destinations = ends.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        List<CityRailDto.Departures> departures = days.stream().map(day -> {
            List<Leg> running = legs.stream()
                    .filter(l -> services.getOrDefault(l.train().service(), Set.of()).contains(day.date())).toList();
            int from = (int) running.stream().filter(Leg::fromCity).count();
            return new CityRailDto.Departures(from, running.size() - from);
        }).toList();
        return new CityRailDto.Trains(destinations, departures);
    }

    /** A shape from a station on. */
    private static List<double[]> from(Station station, List<double[]> shape) {
        return shape.subList(nearest(shape, station), shape.size());
    }

    /** The point of a track nearest to a station. */
    private static int nearest(List<double[]> points, Station station) {
        int best = 0;
        for (int i = 1; i < points.size(); i++) {
            if (Geo.meters(points.get(i), station.at()) < Geo.meters(points.get(best), station.at())) {
                best = i;
            }
        }
        return best;
    }

    private static double nearestMeters(List<double[]> points, Station station) {
        return Geo.meters(points.get(nearest(points, station)), station.at());
    }

    /** Whether a station is on a track. */
    private static boolean onTrack(List<double[]> points, Station station) {
        double[] p = Geo.xy(station.lat(), station.lon());
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            if (Geo.toSegment(p, Geo.xy(a[0], a[1]), Geo.xy(b[0], b[1])) < AT_STATION_METERS) {
                return true;
            }
        }
        return false;
    }


    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    /** Every train with its calls, platforms counting as their station. */
    private List<Train> trains() {
        return railTrains.all().stream().map(t -> new Train(t.agency(), t.service(), t.shape(), t.track(),
                t.calls().stream().map(c -> new Station(c.station(), c.name(), c.lat(), c.lon())).toList())).toList();
    }
}
