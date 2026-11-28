package com.transitanalytics.rail;

import com.transitanalytics.data.RailTrains;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.domain.CityTime;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.rail.JourneyPlanner.Call;
import com.transitanalytics.rail.JourneyPlanner.Change;
import com.transitanalytics.rail.JourneyPlanner.Journey;
import com.transitanalytics.rail.JourneyPlanner.Ride;
import com.transitanalytics.timetable.Periods;
import com.transitanalytics.timetable.ServiceDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/**
 * Train against bus between the railway stations in the city: for every pair, the fastest journey by Polregio train
 * and the fastest off-peak journey by bus between the bus stops nearest the two stations, each with up to two changes,
 * on the weekday the City Rail page counts trains on.
 */
@Service
public class RailBusComparison {

    private static final Logger log = LoggerFactory.getLogger(RailBusComparison.class);

    /** Polregio's agency id in the feed. */
    private static final String POLREGIO = "PR";
    static final int MAX_CHANGES = 2;
    /** Changing trains at a station. */
    static final int TRAIN_CHANGE_SECONDS = 180;
    /** Changing buses at the same stop. */
    static final int BUS_CHANGE_SECONDS = 60;
    /** Changing buses at another stop: at most this far, walking at 1.25 m/s (4.5 km/h) a fifth further than straight. */
    static final double WALK_METERS = 300;
    private static final double WALK_SECONDS_PER_METER = 1.2 / 1.25;
    /** A station's bus stops: the nearest, and those at most this much further away than it. */
    static final double STOP_MARGIN_METERS = 200;
    /** A station whose nearest bus stop is further than this has none. */
    static final double STOP_MAX_METERS = 1000;
    /** Rides leaving a stop later than this after one can board there aren't considered. */
    private static final int BUS_HORIZON_SECONDS = 3600;
    private static final int TRAIN_HORIZON_SECONDS = 4 * 3600;

    private final JdbcTemplate jdbc;
    private final RailTrains railTrains;
    private final Clock clock;
    private final CityRailService rail;
    private final CityRailProperties network;
    private volatile Cached cached;

    private record Cached(LocalDate today, CityRailDto.Comparison comparison) {
    }

    /** A stop or station of the timetable. */
    private record Place(long id, String name, double lat, double lon) {

        double[] point() {
            return new double[] {lat, lon};
        }
    }

    /** A day's rides between numbered places. */
    private record Network(List<Place> places, List<Ride> rides) {
    }

    private final boolean warmUp;

    public RailBusComparison(JdbcTemplate jdbc, RailTrains railTrains, Clock clock, CityRailService rail,
                             CityRailProperties network, @Value("${app.warm-up.enabled:true}") boolean warmUp) {
        this.jdbc = jdbc;
        this.railTrains = railTrains;
        this.clock = clock;
        this.rail = rail;
        this.network = network;
        this.warmUp = warmUp;
    }

    /** Works the comparison out at startup, in the background: it takes some seconds. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        WarmUp.inBackground("train-against-bus", RailBusComparison.class, this::comparison);
    }

    /** Any timetable may change it: worked out again, in the background. */
    @EventListener
    public void afterImport(GtfsImported imported) {
        cached = null;
        if (warmUp) {
            warmUp();
        }
    }

    /** The comparison for today, kept until the next day or import of any timetable; worked out by one caller at a time. */
    public synchronized CityRailDto.Comparison comparison() {
        LocalDate today = ServiceDay.serviceDay(clock.instant());
        Cached current = cached;
        if (current == null || !current.today().equals(today)) {
            current = new Cached(today, compare());
            cached = current;
        }
        return current.comparison();
    }

    private CityRailDto.Comparison compare() {
        LocalDate day = rail.report().days().stream().filter(d -> "weekday".equals(d.kind()))
                .map(CityRailDto.Day::date).findFirst().orElse(null);
        if (day == null) {
            return new CityRailDto.Comparison(null, CityTime.OFF_PEAK, MAX_CHANGES, List.of(), List.of());
        }
        long started = System.nanoTime();
        Network trains = trains(day);
        Network buses = buses(day);
        List<Integer> stations = stations(trains);
        Map<Integer, List<Integer>> stops = new LinkedHashMap<>();
        stations.forEach(s -> stops.put(s, nearStops(trains.places().get(s), buses.places())));

        JourneyPlanner byTrain = new JourneyPlanner(trains.places().size(), trains.rides(),
                sameStop(trains.places().size(), TRAIN_CHANGE_SECONDS), MAX_CHANGES + 1, TRAIN_HORIZON_SECONDS);
        JourneyPlanner byBus = new JourneyPlanner(buses.places().size(), buses.rides(), walks(buses.places()),
                MAX_CHANGES + 1, BUS_HORIZON_SECONDS);
        List<Periods.Window> offPeak = Periods.OFF_PEAK;
        IntPredicate anyTime = t -> true;
        IntPredicate offPeakTime = t -> Periods.in(offPeak, t);

        List<CityRailDto.Pair> pairs = new ArrayList<>();
        for (int from : stations) {
            Map<String, Set<Integer>> trainTargets = new LinkedHashMap<>();
            Map<String, Set<Integer>> busTargets = new LinkedHashMap<>();
            for (int to : stations) {
                if (to != from) {
                    trainTargets.put(name(trains, to), Set.of(to));
                    busTargets.put(name(trains, to), Set.copyOf(stops.get(to)));
                }
            }
            Map<String, Journey> train = byTrain.fastest(Set.of(from), trainTargets, anyTime);
            Map<String, Journey> bus = byBus.fastest(Set.copyOf(stops.get(from)), busTargets, offPeakTime);
            for (String to : trainTargets.keySet()) {
                pairs.add(new CityRailDto.Pair(name(trains, from), to, dto(train.get(to), trains),
                        dto(bus.get(to), buses)));
            }
        }
        List<CityRailDto.ComparedStation> compared = stations.stream().map(s -> new CityRailDto.ComparedStation(
                name(trains, s), stops.get(s).stream().map(b -> new CityRailDto.NearStop(buses.places().get(b).name(),
                        (int) Math.round(Geo.meters(trains.places().get(s).point(), buses.places().get(b).point()))))
                .toList())).toList();
        log.info("Train against bus: {} stations compared in {} ms", stations.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new CityRailDto.Comparison(day, CityTime.OFF_PEAK, MAX_CHANGES, compared, pairs);
    }

    // --- the stations and their bus stops ------------------------------------------------------------------------

    /** The stations in the city that Polregio's trains call at: the hub first, then by name. */
    private List<Integer> stations(Network trains) {
        Set<Integer> called = trains.rides().stream().flatMap(r -> r.calls().stream()).map(Call::stop)
                .collect(Collectors.toSet());
        List<Long> hub = jdbc.queryForList("SELECT id FROM stop WHERE feed = ? AND gtfs_id = ?", Long.class,
                TimetableFeed.RAIL, network.hub());
        return called.stream().filter(s -> {
                    Place p = trains.places().get(s);
                    return CityBoundaries.inCity(p.lat(), p.lon());
                })
                .sorted(Comparator.comparing((Integer s) -> !hub.contains(trains.places().get(s).id()))
                        .thenComparing(s -> trains.places().get(s).name()))
                .toList();
    }

    /** The bus stops nearest a station: the nearest, and those not much further away. */
    static List<Integer> nearStops(Place station, List<Place> stops) {
        double nearest = Double.MAX_VALUE;
        double[] distance = new double[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            distance[i] = Geo.meters(station.point(), stops.get(i).point());
            nearest = Math.min(nearest, distance[i]);
        }
        if (nearest > STOP_MAX_METERS) {
            return List.of();
        }
        List<Integer> near = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            if (distance[i] <= nearest + STOP_MARGIN_METERS) {
                near.add(i);
            }
        }
        near.sort(Comparator.comparingDouble(i -> distance[i]));
        return near;
    }

    // --- changes -------------------------------------------------------------------------------------------------

    private static List<List<Change>> sameStop(int places, int seconds) {
        List<List<Change>> changes = new ArrayList<>(places);
        for (int p = 0; p < places; p++) {
            changes.add(List.of(new Change(p, seconds)));
        }
        return changes;
    }

    /** Changing at the same stop, or walking to another one nearby. */
    static List<List<Change>> walks(List<Place> stops) {
        List<List<Change>> changes = new ArrayList<>(stops.size());
        for (int a = 0; a < stops.size(); a++) {
            changes.add(new ArrayList<>(List.of(new Change(a, BUS_CHANGE_SECONDS))));
        }
        // Stops by latitude: only those within the walk north and south of a stop are measured.
        Integer[] byLat = new Integer[stops.size()];
        for (int i = 0; i < byLat.length; i++) {
            byLat[i] = i;
        }
        Arrays.sort(byLat, Comparator.comparingDouble(i -> stops.get(i).lat()));
        double degrees = WALK_METERS / Geo.M_PER_DEG;
        for (int i = 0; i < byLat.length; i++) {
            Place a = stops.get(byLat[i]);
            for (int j = i + 1; j < byLat.length && stops.get(byLat[j]).lat() - a.lat() <= degrees; j++) {
                double meters = Geo.meters(a.point(), stops.get(byLat[j]).point());
                if (meters <= WALK_METERS) {
                    int seconds = BUS_CHANGE_SECONDS + (int) Math.round(meters * WALK_SECONDS_PER_METER);
                    changes.get(byLat[i]).add(new Change(byLat[j], seconds));
                    changes.get(byLat[j]).add(new Change(byLat[i], seconds));
                }
            }
        }
        return changes;
    }

    // --- the timetables ------------------------------------------------------------------------------------------

    /** Polregio's trains of the day, between stations (platforms count as their station). */
    private Network trains(LocalDate day) {
        Map<Long, Integer> numbers = new HashMap<>();
        List<Place> places = new ArrayList<>();
        List<Ride> rides = new ArrayList<>();
        for (RailTrains.Train train : railTrains.of(day)) {
            if (!POLREGIO.equals(train.agency()) || train.calls().size() < 2) {
                continue;
            }
            List<Call> calls = new ArrayList<>();
            for (RailTrains.Call c : train.calls()) {
                int place = numbers.computeIfAbsent(c.place(), id -> {
                    places.add(new Place(id, c.name(), c.lat(), c.lon()));
                    return places.size() - 1;
                });
                calls.add(new Call(place, c.arrival(), c.departure(), c.boards(), c.alights()));
            }
            rides.add(new Ride(train.name(), calls));
        }
        return new Network(places, rides);
    }

    /** Every bus of the day, of all the bus timetables. */
    private Network buses(LocalDate day) {
        return network("""
                SELECT t.id, coalesce(nullif(r.short_name, ''), r.long_name) AS line, s.id AS place, %s
                FROM trip t JOIN route r ON r.id = t.route_id
                     %s
                WHERE t.feed <> ? AND %s""", day, TimetableFeed.RAIL);
    }

    /** The trips of a query (one row per call), as rides: one row per trip, its calls as arrays. */
    private Network network(String template, LocalDate day, Object... args) {
        String calls = template.formatted("""
                st.stop_sequence,
                extract(epoch FROM coalesce(st.arrival_time, st.departure_time)::interval)::int AS arrival,
                extract(epoch FROM coalesce(st.departure_time, st.arrival_time)::interval)::int AS departure,
                coalesce(st.pickup_type, 0) <> 1 AS boards, coalesce(st.drop_off_type, 0) <> 1 AS alights""", """
                JOIN calendar_date c ON c.feed = t.feed AND c.service_id = t.service_id AND c.exception_type = 1
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id""",
                "c.date = ? AND coalesce(st.arrival_time, st.departure_time) IS NOT NULL");
        String sql = """
                SELECT line, array_agg(place ORDER BY stop_sequence), array_agg(arrival ORDER BY stop_sequence),
                       array_agg(departure ORDER BY stop_sequence), array_agg(boards ORDER BY stop_sequence),
                       array_agg(alights ORDER BY stop_sequence)
                FROM (%s) calls GROUP BY id, line HAVING count(*) > 1""".formatted(calls);
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = day;

        Map<Long, Integer> numbers = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        List<Ride> rides = new ArrayList<>();
        jdbc.query(sql, rs -> {
            Long[] places = (Long[]) rs.getArray(2).getArray();
            Integer[] arrivals = (Integer[]) rs.getArray(3).getArray();
            Integer[] departures = (Integer[]) rs.getArray(4).getArray();
            Boolean[] boards = (Boolean[]) rs.getArray(5).getArray();
            Boolean[] alights = (Boolean[]) rs.getArray(6).getArray();
            List<Call> list = new ArrayList<>(places.length);
            for (int i = 0; i < places.length; i++) {
                int place = numbers.computeIfAbsent(places[i], id -> {
                    ids.add(id);
                    return ids.size() - 1;
                });
                list.add(new Call(place, arrivals[i], departures[i], boards[i], alights[i]));
            }
            rides.add(new Ride(rs.getString(1), list));
        }, all);
        return new Network(places(ids), rides);
    }

    /** The places of the ids, in the same order. */
    private List<Place> places(List<Long> ids) {
        Map<Long, Place> byId = new HashMap<>();
        jdbc.query("SELECT id, name, latitude, longitude FROM stop WHERE id = ANY (?)",
                rs -> {
                    byId.put(rs.getLong("id"), new Place(rs.getLong("id"), rs.getString("name"),
                            rs.getDouble("latitude"), rs.getDouble("longitude")));
                }, (Object) ids.toArray(Long[]::new));
        return ids.stream().map(byId::get).toList();
    }

    // --- the answer ----------------------------------------------------------------------------------------------

    private static String name(Network network, int place) {
        return network.places().get(place).name();
    }

    private static CityRailDto.Journey dto(Journey journey, Network network) {
        if (journey == null) {
            return null;
        }
        List<CityRailDto.Leg> legs = journey.legs().stream().map(l -> new CityRailDto.Leg(l.ride().route(),
                name(network, l.on().stop()), name(network, l.off().stop()), hhmm(l.on().departure()),
                hhmm(l.off().arrival()))).toList();
        return new CityRailDto.Journey((int) Math.round(journey.seconds() / 60.0), hhmm(journey.departure()),
                hhmm(journey.arrival()), legs);
    }

    /** "HH:MM", past midnight from 00:00 again. */
    static String hhmm(int seconds) {
        return "%02d:%02d".formatted(seconds / 3600 % 24, seconds / 60 % 60);
    }
}
