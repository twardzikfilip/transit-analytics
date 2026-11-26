package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.geo.PlaceData;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.gtfs.PublicHolidays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The railways in and around Białystok and the Polregio and PKP Intercity trains on them, up to the ends of the network
 * ({@link CityRailProperties}). The timetable doesn't name lines there, so the routes are worked out here: one to each
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

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CityRailProperties network;
    private final List<CityRailDto.Railway> railways =
            List.of(PlaceData.json("osm/railways.json", CityRailDto.Railway[].class));
    private volatile Cached cached;

    private record Cached(LocalDate day, CityRailDto.Report report) {
    }

    public CityRailService(JdbcTemplate jdbc, Clock clock, CityRailProperties network) {
        this.jdbc = jdbc;
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
        Map<String, Set<LocalDate>> services = services(today);
        List<CityRailDto.Day> days = days(services.values().stream().flatMap(Set::stream).distinct().sorted().toList());
        return new CityRailDto.Report(railways, days, routes(services, days));
    }

    // --- the days ------------------------------------------------------------------------------------------------

    /** The dates from today on which each service runs. */
    private Map<String, Set<LocalDate>> services(LocalDate today) {
        Map<String, Set<LocalDate>> dates = new HashMap<>();
        jdbc.query("SELECT service_id, date FROM calendar_date WHERE feed = ? AND exception_type = 1 AND date >= ?",
                rs -> {
                    dates.computeIfAbsent(rs.getString("service_id"), k -> new HashSet<>())
                            .add(rs.getObject("date", LocalDate.class));
                }, TimetableFeed.RAIL, today);
        return dates;
    }

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

    /** A station a train calls at. */
    private record Call(String id, String name, double lat, double lon) {

        double[] place() {
            return new double[] {lat, lon};
        }
    }

    /** A train's calls. */
    private record Train(String agency, String service, String shape, List<Call> calls) {
    }

    /** The part of a train's run on one side of Białystok, seen from there outwards: its calls and its track. */
    private record Leg(Train train, boolean fromCity, List<Call> calls, List<double[]> track) {

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    /** A route being put together: its end, its track up to there, and its trains. */
    private record Line(Call end, List<double[]> points, List<Leg> legs) {
    }

    private List<CityRailDto.Route> routes(Map<String, Set<LocalDate>> services, List<CityRailDto.Day> days) {
        Map<String, Line> lines = new LinkedHashMap<>();
        List<Leg> shorter = new ArrayList<>();
        List<Call> ends = ends();
        for (Leg leg : legs()) {
            Call end = ends.stream().filter(e -> nearestMeters(leg.track(), e) < AT_STATION_METERS).findFirst()
                    .orElse(null);
            if (end == null) {
                shorter.add(leg);
                continue;
            }
            lines.computeIfAbsent(end.id(), k -> new Line(end,
                    leg.track().subList(0, nearest(leg.track(), end) + 1), new ArrayList<>())).legs().add(leg);
        }
        // Trains that turn back before the end of the network.
        for (Leg leg : shorter) {
            lines.values().stream().filter(l -> onTrack(l.points(), leg.last())).findFirst()
                    .ifPresentOrElse(l -> l.legs().add(leg),
                            () -> log.warn("City rail: a train to {} is on no route", leg.last().name()));
        }
        return lines.values().stream().map(l -> route(l, services, days))
                .sorted(Comparator.comparing(CityRailDto.Route::id)).toList();
    }

    private List<Leg> legs() {
        Map<String, List<double[]>> shapes = shapes();
        Map<String, List<double[]>> tracks = new HashMap<>();
        List<Leg> legs = new ArrayList<>();
        for (Train train : trains()) {
            List<double[]> shape = shapes.get(train.shape());
            int at = train.calls().stream().map(Call::id).toList().indexOf(network.hub());
            if (shape == null || at < 0) {
                continue;
            }
            Call hub = train.calls().get(at);
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

    private static CityRailDto.Route route(Line line, Map<String, Set<LocalDate>> services,
                                           List<CityRailDto.Day> days) {
        // Fast trains pass some stations: those of all trains, in order along the track.
        Map<String, Call> calls = new LinkedHashMap<>();
        line.legs().forEach(l -> l.calls().stream().filter(c -> onTrack(line.points(), c))
                .forEach(c -> calls.putIfAbsent(c.id(), c)));
        calls.putIfAbsent(line.end().id(), line.end());
        List<CityRailDto.Station> stations = calls.values().stream()
                .sorted(Comparator.comparingInt(c -> nearest(line.points(), c)))
                .map(c -> new CityRailDto.Station(c.name(), c.lat(), c.lon())).toList();
        return new CityRailDto.Route(line.end().name(), stations, line.points(),
                trains(line.legs().stream().filter(l -> POLREGIO.equals(l.train().agency())).toList(), services, days),
                trains(line.legs(), services, days));
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
    private static List<double[]> from(Call station, List<double[]> shape) {
        return shape.subList(nearest(shape, station), shape.size());
    }

    /** The point of a track nearest to a station. */
    private static int nearest(List<double[]> points, Call station) {
        int best = 0;
        for (int i = 1; i < points.size(); i++) {
            if (Geo.meters(points.get(i), station.place()) < Geo.meters(points.get(best), station.place())) {
                best = i;
            }
        }
        return best;
    }

    private static double nearestMeters(List<double[]> points, Call station) {
        return Geo.meters(points.get(nearest(points, station)), station.place());
    }

    /** Whether a station is on a track. */
    private static boolean onTrack(List<double[]> points, Call station) {
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

    /** The stations the network ends at. */
    private List<Call> ends() {
        return jdbc.query("""
                SELECT gtfs_id, name, latitude, longitude FROM stop
                WHERE feed = ? AND gtfs_id = ANY (?) ORDER BY gtfs_id""",
                (rs, i) -> new Call(rs.getString("gtfs_id"), rs.getString("name"), rs.getDouble("latitude"),
                        rs.getDouble("longitude")),
                TimetableFeed.RAIL, network.ends().toArray(String[]::new));
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    /** Every train with its calls, platforms counting as their station. */
    private List<Train> trains() {
        Map<Long, Train> trains = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, a.gtfs_id AS agency, t.service_id, t.shape_id, coalesce(p.gtfs_id, s.gtfs_id) AS station,
                       coalesce(p.name, s.name) AS name, coalesce(p.latitude, s.latitude) AS lat,
                       coalesce(p.longitude, s.longitude) AS lon
                FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                     LEFT JOIN stop p ON p.feed = s.feed AND p.gtfs_id = s.parent_station
                WHERE t.feed = ?
                ORDER BY t.id, st.stop_sequence""",
                rs -> {
                    String agency = rs.getString("agency");
                    String service = rs.getString("service_id");
                    String shape = rs.getString("shape_id");
                    trains.computeIfAbsent(rs.getLong("id"), k -> new Train(agency, service, shape, new ArrayList<>()))
                            .calls()
                            .add(new Call(rs.getString("station"), rs.getString("name"), rs.getDouble("lat"),
                                    rs.getDouble("lon")));
                }, TimetableFeed.RAIL);
        return List.copyOf(trains.values());
    }

    private Map<String, List<double[]>> shapes() {
        Map<String, List<double[]>> shapes = new HashMap<>();
        jdbc.query("SELECT shape_id, latitude, longitude FROM shape_point WHERE feed = ? ORDER BY shape_id, sequence",
                rs -> {
                    shapes.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")});
                }, TimetableFeed.RAIL);
        return shapes;
    }
}
