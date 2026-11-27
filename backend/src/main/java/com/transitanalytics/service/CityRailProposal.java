package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.dto.CityRailProposalDto;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.geo.PlaceData;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.service.ProposalScheduler.Pass;
import com.transitanalytics.service.ProposalScheduler.Run;
import com.transitanalytics.service.ProposalScheduler.Segment;
import com.transitanalytics.service.ProposalScheduler.Timetable;
import com.transitanalytics.service.ProposalScheduler.Trip;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;

/**
 * City Rail proposal no. 1: city trains on the five railways from Białystok, shortened to Klepacze, Fasty, Lewickie,
 * Wasilków and Kuriany (the railway to Kuriany has no passenger trains today), calling at every station, new ones
 * included ({@code places/osm/city-rail-proposal.json}, built by {@code tools/geo/build_city_rail_proposal.py}).
 * Two ways of running them: five lines from Białystok, or lines running through Białystok (and one ending there). Each
 * is timetabled ({@link ProposalScheduler}) around the trains of the weekday the City Rail page counts trains on.
 * <p>
 * Run times come from Polregio's: between two stations its trains call at one after the other, the median of their
 * times; a new station on the way shares it out by distance and adds the time lost braking and starting again. Where
 * no Polregio train runs (to Kuriany), from the distance, a top speed of 80 km/h, and starting and braking at
 * 0.6 m/s², scaled as Polregio's times compare to that elsewhere. Run times are whole minutes.
 */
@Service
public class CityRailProposal {

    private static final Logger log = LoggerFactory.getLogger(CityRailProposal.class);

    private static final String POLREGIO = "PR";
    /** The station whose dwell the new stations have. */
    static final String STAROSIELCE = "24042";
    /** A train running through Białystok stands there this long. */
    static final int HUB_DWELL = 120;
    static final double SPEED = 80 / 3.6;
    static final double ACCELERATION = 0.6;
    static final double BRAKING = 0.6;
    /** A station of the network at most this far from a train's track is on its way. */
    static final double ON_TRACK_METERS = 150;
    /** A call at most this far along the track from a station of the network is at it. */
    private static final double AT_CALL_METERS = 500;

    /** The network as built. */
    record NetworkFile(List<CorridorFile> corridors) {
    }

    record CorridorFile(String id, String ref, List<StationFile> stations, List<double[]> points) {
    }

    /** @param id the station's id in the timetable, null for one no train calls at today */
    record StationFile(String id, String name, String kind, double lat, double lon, double km) {

        double[] point() {
            return new double[] {lat, lon};
        }
    }

    /** Part of a line: a railway, outwards from Białystok or inwards to it. */
    record Section(String corridor, boolean outward) {
    }

    record LineDef(String id, List<Section> sections) {
    }

    /** The two solutions: five lines from Białystok; or two lines through it, and one ending there. */
    static final Map<String, List<LineDef>> SOLUTIONS = Map.of(
            "radial", List.of(
                    new LineDef("S1", List.of(new Section("klepacze", true))),
                    new LineDef("S2", List.of(new Section("fasty", true))),
                    new LineDef("S3", List.of(new Section("lewickie", true))),
                    new LineDef("S4", List.of(new Section("wasilkow", true))),
                    new LineDef("S5", List.of(new Section("kuriany", true)))),
            "through", List.of(
                    new LineDef("S1", List.of(new Section("wasilkow", false), new Section("lewickie", true))),
                    new LineDef("S2", List.of(new Section("fasty", false), new Section("kuriany", true))),
                    new LineDef("S3", List.of(new Section("klepacze", true)))));
    private static final List<String> SOLUTION_ORDER = List.of("radial", "through");

    /** A train's call at a station. */
    record Call(String station, double lat, double lon, int arrival, int departure) {

        double[] point() {
            return new double[] {lat, lon};
        }
    }

    record Train(long id, String agency, String shape, List<Call> calls) {
    }

    /** A train at a station of the network: when it gets there and when it leaves (the same if it passes). */
    record At(String station, double along, int arrive, int depart) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CityRailService rail;
    private final boolean warmUp;
    private final List<CorridorFile> corridors =
            List.copyOf(PlaceData.json("osm/city-rail-proposal.json", NetworkFile.class).corridors());
    private volatile Cached cached;

    private record Cached(LocalDate today, CityRailProposalDto.Proposal proposal) {
    }

    public CityRailProposal(JdbcTemplate jdbc, Clock clock, CityRailService rail,
                            @Value("${app.warm-up.enabled:true}") boolean warmUp) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rail = rail;
        this.warmUp = warmUp;
    }

    /** Works the proposal out at startup, in the background. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        WarmUp.inBackground("city-rail-proposal", CityRailProposal.class, this::proposal);
    }

    @EventListener
    public void afterImport(GtfsImported imported) {
        if (TimetableFeed.RAIL.equals(imported.feed())) {
            cached = null;
            if (warmUp) {
                warmUp();
            }
        }
    }

    /** The proposal for today, kept until the next day or import of the trains' timetable. */
    public synchronized CityRailProposalDto.Proposal proposal() {
        LocalDate today = ServiceDay.serviceDay(clock.instant());
        Cached current = cached;
        if (current == null || !current.today().equals(today)) {
            current = new Cached(today, propose());
            cached = current;
        }
        return current.proposal();
    }

    private CityRailProposalDto.Proposal propose() {
        LocalDate day = rail.report().days().stream().filter(d -> "weekday".equals(d.kind()))
                .map(CityRailDto.Day::date).findFirst().orElse(null);
        if (day == null) {
            return new CityRailProposalDto.Proposal(null, rules(60, 0), corridorDtos(Set.of()), List.of());
        }
        long started = System.nanoTime();
        List<Train> trains = trains(day);
        Map<String, List<double[]>> shapes = shapes(trains);
        List<Pass> running = new ArrayList<>();
        Set<Long> considered = new HashSet<>();
        for (Train train : trains) {
            List<Pass> passes = passes(train.id(), along(shapes.get(train.shape()), train.calls()));
            if (!passes.isEmpty()) {
                considered.add(train.id());
                running.addAll(passes);
            }
        }
        Set<String> served = new HashSet<>();
        running.forEach(p -> served.add(p.segment().railway()));

        List<Train> polregio = trains.stream().filter(t -> POLREGIO.equals(t.agency())).toList();
        int newDwell = minutes(median(dwells(polregio, STAROSIELCE), 60));
        RunTimes times = runTimes(polregio, newDwell);
        List<CityRailProposalDto.Solution> solutions = new ArrayList<>();
        for (String id : SOLUTION_ORDER) {
            ProposalScheduler scheduler = new ProposalScheduler(running);
            List<Timetable> timetables = new ArrayList<>();
            for (LineDef line : SOLUTIONS.get(id)) {
                timetables.add(scheduler.schedule(run(line, true, times), run(line, false, times)));
            }
            List<CityRailProposalDto.Line> lines = new ArrayList<>();
            for (int i = 0; i < timetables.size(); i++) {
                lines.add(lineDto(SOLUTIONS.get(id).get(i), timetables.get(i), scheduler));
            }
            solutions.add(new CityRailProposalDto.Solution(id, lines));
        }
        log.info("City rail proposal: {} trains kept clear of, worked out in {} ms", considered.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new CityRailProposalDto.Proposal(day, rules(newDwell, considered.size()), corridorDtos(served),
                solutions);
    }

    private static CityRailProposalDto.Rules rules(int newDwell, int trains) {
        return new CityRailProposalDto.Rules(ProposalScheduler.HEADWAY / 60, ProposalScheduler.TURNAROUND / 60,
                ProposalScheduler.MAX_INTERVAL / 60, hhmm(ProposalScheduler.FIRST), hhmm(ProposalScheduler.LAST),
                newDwell, HUB_DWELL, trains);
    }

    private List<CityRailProposalDto.Corridor> corridorDtos(Set<String> served) {
        return corridors.stream().map(c -> new CityRailProposalDto.Corridor(c.id(), c.ref(), served.contains(c.id()),
                c.stations().stream().map(s -> new CityRailProposalDto.Station(s.name(), s.kind(), s.lat(), s.lon(),
                        s.km())).toList(), c.points())).toList();
    }

    // --- where today's trains run ----------------------------------------------------------------------------------

    /**
     * When a train is at each station of the network on its way, in order: at the stations it calls at, its times;
     * at those it passes, as far between its calls before and after as the station lies between them on its track.
     * Without its track, only the stations it calls at.
     */
    List<At> along(List<double[]> shape, List<Call> calls) {
        Map<String, double[]> stations = stationPoints();
        Map<String, String> names = stationNames();
        if (shape == null || shape.size() < 2) {
            List<At> at = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                Call c = calls.get(i);
                if (names.containsKey(c.station())) {
                    at.add(new At(names.get(c.station()), i, c.arrival(), c.departure()));
                }
            }
            return at;
        }
        double[][] xy = shape.stream().map(p -> Geo.xy(p[0], p[1])).toArray(double[][]::new);
        double[] chain = new double[xy.length];
        for (int i = 1; i < xy.length; i++) {
            chain[i] = chain[i - 1] + Math.hypot(xy[i][0] - xy[i - 1][0], xy[i][1] - xy[i - 1][1]);
        }
        // The calls, in order along the track (a train may pass the same place twice, e.g. turning at Białystok).
        double[] callAlong = new double[calls.size()];
        int from = 0;
        for (int i = 0; i < calls.size(); i++) {
            double[] p = Geo.xy(calls.get(i).lat(), calls.get(i).lon());
            double best = Double.MAX_VALUE;
            int bestSegment = from;
            for (int j = from; j < xy.length - 1; j++) {
                double d = Geo.toSegment(p, xy[j], xy[j + 1]);
                if (d < best) {
                    best = d;
                    bestSegment = j;
                } else if (best <= AT_CALL_METERS && d > 4 * AT_CALL_METERS) {
                    break;
                }
            }
            callAlong[i] = along(p, xy, chain, bestSegment);
            from = bestSegment;
        }
        List<At> found = new ArrayList<>();
        stations.forEach((name, point) -> {
            double[] p = Geo.xy(point[0], point[1]);
            int nearest = -1;
            double nearestMeters = Double.MAX_VALUE;
            for (int j = 0; j <= xy.length - 1; j++) {
                double d = j < xy.length - 1 ? Geo.toSegment(p, xy[j], xy[j + 1]) : Double.MAX_VALUE;
                if (d <= ON_TRACK_METERS && d < nearestMeters) {
                    nearest = j;
                    nearestMeters = d;
                } else if (d > ON_TRACK_METERS && nearest >= 0) {
                    // One visit ends: the next may come back past it.
                    at(name, along(p, xy, chain, nearest), calls, callAlong, names).ifPresent(found::add);
                    nearest = -1;
                    nearestMeters = Double.MAX_VALUE;
                }
            }
        });
        found.sort(Comparator.comparingDouble(At::along));
        return found;
    }

    private static Optional<At> at(String station, double along, List<Call> calls, double[] callAlong,
                                             Map<String, String> names) {
        for (int i = 0; i < calls.size(); i++) {
            if (station.equals(names.get(calls.get(i).station())) && Math.abs(callAlong[i] - along) < AT_CALL_METERS) {
                return Optional.of(new At(station, along, calls.get(i).arrival(), calls.get(i).departure()));
            }
        }
        for (int i = 0; i < calls.size() - 1; i++) {
            if (callAlong[i] <= along && along <= callAlong[i + 1] && callAlong[i + 1] > callAlong[i]) {
                double k = (along - callAlong[i]) / (callAlong[i + 1] - callAlong[i]);
                int time = (int) Math.round(calls.get(i).departure()
                        + k * (calls.get(i + 1).arrival() - calls.get(i).departure()));
                return Optional.of(new At(station, along, time, time));
            }
        }
        return Optional.empty(); // before the train's first call or after its last
    }

    private static double along(double[] p, double[][] xy, double[] chain, int segment) {
        double[] a = xy[segment];
        double[] b = xy[segment + 1];
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length2 = dx * dx + dy * dy;
        double k = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2));
        return chain[segment] + k * (chain[segment + 1] - chain[segment]);
    }

    /**
     * A train's stretches between neighbouring stations of a railway of the network. Near Białystok some railways
     * run side by side between the same stations (each on its own track): the train's is the one it goes on along.
     */
    List<Pass> passes(long train, List<At> at) {
        Map<String, Set<String>> neighbours = neighbours();
        List<Set<String>> candidates = new ArrayList<>();
        for (int i = 0; i < at.size() - 1; i++) {
            candidates.add(neighbours.getOrDefault(at.get(i).station() + "|" + at.get(i + 1).station(), Set.of()));
        }
        List<Pass> passes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Set<String> railways = new TreeSet<>(candidates.get(i));
            for (int other : new int[] {i + 1, i - 1}) {
                if (railways.size() > 1 && other >= 0 && other < candidates.size()) {
                    Set<String> both = new TreeSet<>(railways);
                    both.retainAll(candidates.get(other));
                    if (!both.isEmpty()) {
                        railways = both;
                    }
                }
            }
            if (!railways.isEmpty()) {
                At a = at.get(i);
                At b = at.get(i + 1);
                passes.add(new Pass(new Segment(railways.iterator().next(), a.station(), b.station()), a.depart(),
                        b.arrive(), (int) train));
            }
        }
        return passes;
    }

    /** Per pair of neighbouring stations ("a|b", both ways), the railways they neighbour on. */
    private Map<String, Set<String>> neighbours() {
        Map<String, Set<String>> neighbours = new HashMap<>();
        for (CorridorFile c : corridors) {
            for (int i = 0; i < c.stations().size() - 1; i++) {
                String a = c.stations().get(i).name();
                String b = c.stations().get(i + 1).name();
                neighbours.computeIfAbsent(a + "|" + b, k -> new HashSet<>()).add(c.id());
                neighbours.computeIfAbsent(b + "|" + a, k -> new HashSet<>()).add(c.id());
            }
        }
        return neighbours;
    }

    private Map<String, double[]> stationPoints() {
        Map<String, double[]> points = new LinkedHashMap<>();
        corridors.forEach(c -> c.stations().forEach(s -> points.putIfAbsent(s.name(), s.point())));
        return points;
    }

    /** Per timetable id, the station's name in the network. */
    private Map<String, String> stationNames() {
        Map<String, String> names = new HashMap<>();
        corridors.forEach(c -> c.stations().stream().filter(s -> s.id() != null)
                .forEach(s -> names.put(s.id(), s.name())));
        return names;
    }

    // --- run times -----------------------------------------------------------------------------------------------

    /** Seconds per railway from each station to the next, outwards and inwards, and standing at each station. */
    record RunTimes(Map<String, int[]> out, Map<String, int[]> in, Map<String, Integer> dwell) {
    }

    RunTimes runTimes(List<Train> polregio, int newDwell) {
        Map<String, List<Integer>> between = new HashMap<>();
        for (Train t : polregio) {
            for (int i = 0; i < t.calls().size() - 1; i++) {
                Call a = t.calls().get(i);
                Call b = t.calls().get(i + 1);
                between.computeIfAbsent(a.station() + "|" + b.station(), k -> new ArrayList<>())
                        .add(b.arrival() - a.departure());
            }
        }
        // How Polregio's times compare to the model's.
        List<Double> ratios = new ArrayList<>();
        for (CorridorFile c : corridors) {
            served(c).forEach(pair -> {
                double model = modelSeconds(meters(c, pair[0], pair[1]));
                for (String key : List.of(id(c, pair[0]) + "|" + id(c, pair[1]), id(c, pair[1]) + "|" + id(c, pair[0]))) {
                    if (between.containsKey(key)) {
                        ratios.add(median(between.get(key), 0) / model);
                    }
                }
            });
        }
        Collections.sort(ratios);
        double scale = ratios.isEmpty() ? 1 : ratios.get(ratios.size() / 2);
        double stopLoss = scale * (SPEED / (2 * ACCELERATION) + SPEED / (2 * BRAKING));

        Map<String, int[]> out = new HashMap<>();
        Map<String, int[]> in = new HashMap<>();
        Map<String, Integer> dwell = new HashMap<>();
        for (CorridorFile c : corridors) {
            int n = c.stations().size();
            double[] outSeconds = new double[n - 1];
            double[] inSeconds = new double[n - 1];
            for (int j = 0; j < n - 1; j++) {
                outSeconds[j] = scale * modelSeconds(meters(c, j, j + 1));
                inSeconds[j] = outSeconds[j];
            }
            for (int[] pair : served(c)) {
                List<Integer> outward = between.get(id(c, pair[0]) + "|" + id(c, pair[1]));
                List<Integer> inward = between.get(id(c, pair[1]) + "|" + id(c, pair[0]));
                share(c, pair, outward == null ? null : median(outward, 0), stopLoss, outSeconds);
                share(c, pair, inward == null ? null : median(inward, 0), stopLoss, inSeconds);
            }
            out.put(c.id(), wholeMinutes(outSeconds));
            in.put(c.id(), wholeMinutes(inSeconds));
            for (StationFile s : c.stations()) {
                List<Integer> stands = s.id() == null ? List.of() : dwells(polregio, s.id());
                dwell.putIfAbsent(s.name(), stands.isEmpty() ? newDwell : minutes(median(stands, 0)));
            }
        }
        return new RunTimes(out, in, dwell);
    }

    /** A Polregio time between two served stations, shared out over the stretches between them. */
    private static void share(CorridorFile c, int[] pair, Double seconds, double stopLoss, double[] into) {
        if (seconds == null) {
            return;
        }
        double total = meters(c, pair[0], pair[1]);
        for (int j = pair[0]; j < pair[1]; j++) {
            into[j] = seconds * meters(c, j, j + 1) / total
                    + (j > pair[0] ? stopLoss / 2 : 0) + (j + 1 < pair[1] ? stopLoss / 2 : 0);
        }
    }

    /** Neighbouring stations of a railway that trains call at today, as indexes. */
    private static List<int[]> served(CorridorFile c) {
        List<Integer> served = new ArrayList<>();
        for (int i = 0; i < c.stations().size(); i++) {
            if (c.stations().get(i).id() != null) {
                served.add(i);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < served.size() - 1; i++) {
            pairs.add(new int[] {served.get(i), served.get(i + 1)});
        }
        return pairs;
    }

    private static String id(CorridorFile c, int station) {
        return c.stations().get(station).id();
    }

    private static double meters(CorridorFile c, int a, int b) {
        return Math.abs(c.stations().get(b).km() - c.stations().get(a).km()) * 1000;
    }

    /** From standing at one station to standing at the next: starting, running at top speed, braking. */
    static double modelSeconds(double meters) {
        double starting = SPEED * SPEED / (2 * ACCELERATION) + SPEED * SPEED / (2 * BRAKING);
        if (meters >= starting) {
            return meters / SPEED + SPEED / (2 * ACCELERATION) + SPEED / (2 * BRAKING);
        }
        double peak = Math.sqrt(2 * meters * ACCELERATION * BRAKING / (ACCELERATION + BRAKING));
        return peak / ACCELERATION + peak / BRAKING;
    }

    private static int[] wholeMinutes(double[] seconds) {
        int[] minutes = new int[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            minutes[i] = Math.max(60, (int) Math.round(seconds[i] / 60) * 60);
        }
        return minutes;
    }

    /** Seconds, to whole minutes. */
    private static int minutes(double seconds) {
        return (int) Math.round(seconds / 60) * 60;
    }

    private static List<Integer> dwells(List<Train> trains, String station) {
        List<Integer> dwells = new ArrayList<>();
        trains.forEach(t -> t.calls().stream().filter(c -> c.station().equals(station))
                .forEach(c -> dwells.add(c.departure() - c.arrival())));
        return dwells;
    }

    private static double median(List<Integer> values, double otherwise) {
        if (values.isEmpty()) {
            return otherwise;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    // --- the lines -----------------------------------------------------------------------------------------------

    private CorridorFile corridor(String id) {
        return corridors.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** A line's stations in order, one way, with the railway to each from the one before (null for the first). */
    private List<Map.Entry<StationFile, String>> stops(LineDef line, boolean out) {
        List<Map.Entry<StationFile, String>> stops = new ArrayList<>();
        List<Section> sections = new ArrayList<>(line.sections());
        if (!out) {
            Collections.reverse(sections);
        }
        for (Section section : sections) {
            List<StationFile> stations = new ArrayList<>(corridor(section.corridor()).stations());
            if (section.outward() != out) {
                Collections.reverse(stations);
            }
            for (StationFile s : stations) {
                if (stops.isEmpty() || !stops.get(stops.size() - 1).getKey().name().equals(s.name())) {
                    stops.add(Map.entry(s, stops.isEmpty() ? "" : section.corridor()));
                }
            }
        }
        return stops;
    }

    Run run(LineDef line, boolean out, RunTimes times) {
        List<Map.Entry<StationFile, String>> stops = stops(line, out);
        int n = stops.size();
        int[] arrive = new int[n];
        int[] depart = new int[n];
        List<Segment> segments = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            String railway = stops.get(i).getValue();
            CorridorFile c = corridor(railway);
            int a = index(c, stops.get(i - 1).getKey().name());
            int b = index(c, stops.get(i).getKey().name());
            int seconds = a < b ? times.out().get(railway)[a] : times.in().get(railway)[b];
            arrive[i] = depart[i - 1] + seconds;
            String name = stops.get(i).getKey().name();
            int dwell = i == n - 1 ? 0 : name.equals(corridors.get(0).stations().get(0).name()) ? HUB_DWELL
                    : times.dwell().getOrDefault(name, 60);
            depart[i] = arrive[i] + dwell;
            segments.add(new Segment(railway, stops.get(i - 1).getKey().name(), name));
        }
        return new Run(stops.stream().map(e -> e.getKey().name()).toList(), arrive, depart, segments);
    }

    private static int index(CorridorFile c, String name) {
        for (int i = 0; i < c.stations().size(); i++) {
            if (c.stations().get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException(name + " isn't on the railway to " + c.id());
    }

    private CityRailProposalDto.Line lineDto(LineDef line, Timetable t, ProposalScheduler scheduler) {
        List<Map.Entry<StationFile, String>> stops = stops(line, true);
        List<CityRailProposalDto.LineStation> stations = new ArrayList<>();
        double km = 0;
        for (int i = 0; i < stops.size(); i++) {
            StationFile s = stops.get(i).getKey();
            if (i > 0) {
                StationFile before = stops.get(i - 1).getKey();
                km += Math.abs(s.km() - before.km());
            }
            stations.add(new CityRailProposalDto.LineStation(s.name(), s.kind(), round(km)));
        }
        List<double[]> points = new ArrayList<>();
        for (Section section : line.sections()) {
            List<double[]> p = new ArrayList<>(corridor(section.corridor()).points());
            if (!section.outward()) {
                Collections.reverse(p);
            }
            points.addAll(p);
        }
        int closest = ProposalScheduler.NONE;
        int conflicts = 0;
        List<CityRailProposalDto.Trip> out = new ArrayList<>();
        List<CityRailProposalDto.Trip> back = new ArrayList<>();
        for (boolean outward : new boolean[] {true, false}) {
            Run run = outward ? t.out() : t.back();
            for (Trip trip : outward ? t.outs() : t.backs()) {
                int c = scheduler.closest(run, trip.start(), trip.train());
                closest = Math.min(closest, c);
                boolean conflict = c < ProposalScheduler.HEADWAY;
                conflicts += conflict ? 1 : 0;
                List<String> times = new ArrayList<>();
                for (int i = 0; i < run.stations().size(); i++) {
                    times.add(hhmm(trip.start() + (i == run.stations().size() - 1 ? run.arrive()[i]
                            : run.depart()[i])));
                }
                (outward ? out : back).add(new CityRailProposalDto.Trip(times, conflict));
            }
        }
        IntUnaryOperator minutes = s -> s / 60;
        return new CityRailProposalDto.Line(line.id(), stations, points, round(km),
                round(km / (stations.size() - 1)), minutes.applyAsInt(t.out().duration()),
                minutes.applyAsInt(t.back().duration()), Math.min(t.outs().size(), t.backs().size()),
                Math.max(maxInterval(t.outs()), maxInterval(t.backs())) / 60, t.vehicles(),
                t.minTurnaround() == ProposalScheduler.NONE ? 0 : t.minTurnaround() / 60,
                closest == ProposalScheduler.NONE ? null : closest / 60, conflicts, out, back);
    }

    private static int maxInterval(List<Trip> trips) {
        int max = 0;
        for (int i = 1; i < trips.size(); i++) {
            max = Math.max(max, trips.get(i).start() - trips.get(i - 1).start());
        }
        return max;
    }

    private static double round(double km) {
        return Math.round(km * 100) / 100.0;
    }

    static String hhmm(int seconds) {
        return "%02d:%02d".formatted(seconds / 3600 % 24, seconds / 60 % 60);
    }

    // --- the timetable -------------------------------------------------------------------------------------------

    /** The trains of the day, Polregio's and PKP Intercity's, with their calls (platforms count as their station). */
    private List<Train> trains(LocalDate day) {
        Map<Long, Train> trains = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, a.gtfs_id AS agency, t.shape_id, coalesce(p.gtfs_id, s.gtfs_id) AS station,
                       coalesce(p.latitude, s.latitude) AS lat, coalesce(p.longitude, s.longitude) AS lon,
                       extract(epoch FROM coalesce(st.arrival_time, st.departure_time)::interval)::int AS arrival,
                       extract(epoch FROM coalesce(st.departure_time, st.arrival_time)::interval)::int AS departure
                FROM trip t JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                     JOIN calendar_date c ON c.feed = t.feed AND c.service_id = t.service_id
                          AND c.exception_type = 1 AND c.date = ?
                     JOIN stop_time st ON st.trip_id = t.id JOIN stop s ON s.id = st.stop_id
                     LEFT JOIN stop p ON p.feed = s.feed AND p.gtfs_id = s.parent_station
                WHERE t.feed = ? AND coalesce(st.arrival_time, st.departure_time) IS NOT NULL
                ORDER BY t.id, st.stop_sequence""",
                rs -> {
                    long id = rs.getLong("id");
                    String agency = rs.getString("agency");
                    String shape = rs.getString("shape_id");
                    trains.computeIfAbsent(id, k -> new Train(id, agency, shape, new ArrayList<>())).calls()
                            .add(new Call(rs.getString("station"), rs.getDouble("lat"), rs.getDouble("lon"),
                                    rs.getInt("arrival"), rs.getInt("departure")));
                }, day, TimetableFeed.RAIL);
        return List.copyOf(trains.values());
    }

    private Map<String, List<double[]>> shapes(List<Train> trains) {
        Map<String, List<double[]>> shapes = new HashMap<>();
        String[] ids = trains.stream().map(Train::shape).filter(s -> s != null).distinct().toArray(String[]::new);
        jdbc.query("""
                SELECT shape_id, latitude, longitude FROM shape_point
                WHERE feed = ? AND shape_id = ANY (?) ORDER BY shape_id, sequence""",
                rs -> {
                    shapes.computeIfAbsent(rs.getString("shape_id"), k -> new ArrayList<>())
                            .add(new double[] {rs.getDouble("latitude"), rs.getDouble("longitude")});
                }, TimetableFeed.RAIL, ids);
        return shapes;
    }
}
