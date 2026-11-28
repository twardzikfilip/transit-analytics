package com.transitanalytics.rail.proposal;

import com.transitanalytics.rail.proposal.ProposalNetwork.Corridor;
import com.transitanalytics.rail.proposal.ProposalNetwork.RouteDef;
import com.transitanalytics.rail.proposal.ProposalNetwork.Station;
import com.transitanalytics.rail.proposal.ProposalNetwork.Stop;
import com.transitanalytics.rail.proposal.ProposalScheduler.Run;
import com.transitanalytics.rail.proposal.ProposalScheduler.Segment;
import com.transitanalytics.data.RailTrains.Call;
import com.transitanalytics.data.RailTrains.Train;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.transitanalytics.rail.proposal.ProposalNetwork.PASSED;

/**
 * How long the city trains take, from Polregio's: between two stations its trains call at one after the other, the
 * median of their times; a new station on the way shares it out by distance and adds the time lost braking and
 * starting again (a station passed without stopping takes it off). A new end shares out the time to the station past
 * it. Where no Polregio train runs (to Henrykowo), from the distance, a top speed of 80 km/h, and starting and braking
 * at 0.6 m/s², scaled as Polregio's times compare to that elsewhere. Run times are whole minutes.
 */
final class RunTimeModel {

    static final double SPEED = 80 / 3.6;
    static final double ACCELERATION = 0.6;
    static final double BRAKING = 0.6;
    /** A train running through Białystok stands there this long. */
    static final int HUB_DWELL = 120;
    /** The station whose dwell the new stations have. */
    static final String STAROSIELCE = "24042";

    /** Seconds per railway from each station to the next, outwards and inwards, and standing at each station. */
    record RunTimes(Map<String, int[]> out, Map<String, int[]> in, Map<String, Integer> dwell) {
    }

    private final ProposalNetwork network;
    private final RunTimes times;
    private final int newDwell;

    RunTimeModel(ProposalNetwork network, List<Train> polregio) {
        this.network = network;
        this.newDwell = minutes(median(dwells(polregio, STAROSIELCE), 60));
        this.times = runTimes(polregio);
    }

    /** Seconds a train stands at a new station: as long as Polregio's at Białystok Starosielce. */
    int newDwell() {
        return newDwell;
    }

    /** A route's stations one way, with when a train leaving the first at 0 gets to and leaves each. */
    Run run(RouteDef route, boolean out) {
        List<Stop> stops = network.stops(route, out);
        int n = stops.size();
        int[] arrive = new int[n];
        int[] depart = new int[n];
        List<Segment> segments = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            String railway = stops.get(i).railway();
            Corridor c = network.corridor(railway);
            String from = stops.get(i - 1).station().name();
            String name = stops.get(i).station().name();
            int a = c.index(from);
            int b = c.index(name);
            arrive[i] = depart[i - 1] + (a < b ? times.out().get(railway)[a] : times.in().get(railway)[b]);
            int dwell = i == n - 1 ? 0 : name.equals(network.hub()) ? HUB_DWELL : times.dwell().getOrDefault(name, 60);
            depart[i] = arrive[i] + dwell;
            segments.add(new Segment(railway, from, name));
        }
        return new Run(stops.stream().map(s -> s.station().name()).toList(), arrive, depart, segments);
    }

    private RunTimes runTimes(List<Train> polregio) {
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
        for (Corridor corridor : network.corridors()) {
            List<Station> c = corridor.timed();
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
        for (Corridor corridor : network.corridors()) {
            // With the station past the end, if any: the stretches to it are left out once worked out.
            List<Station> c = corridor.timed();
            int n = c.size();
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
            // Through a station without stopping: no braking into it, nor starting from it.
            for (int j = 1; j < n - 1; j++) {
                if (PASSED.equals(c.get(j).kind())) {
                    for (double[] seconds : List.of(outSeconds, inSeconds)) {
                        seconds[j - 1] -= stopLoss / 2;
                        seconds[j] -= stopLoss / 2;
                    }
                }
            }
            int stretches = corridor.stations().size() - 1;
            out.put(corridor.id(), wholeMinutes(Arrays.copyOf(outSeconds, stretches)));
            in.put(corridor.id(), wholeMinutes(Arrays.copyOf(inSeconds, stretches)));
            for (Station s : corridor.stations()) {
                List<Integer> stands = s.id() == null ? List.of() : dwells(polregio, s.id());
                dwell.putIfAbsent(s.name(), PASSED.equals(s.kind()) ? 0
                        : stands.isEmpty() ? newDwell : minutes(median(stands, 0)));
            }
        }
        return new RunTimes(out, in, dwell);
    }

    /** A Polregio time between two served stations, shared out over the stretches between them. */
    private static void share(List<Station> c, int[] pair, Double seconds, double stopLoss, double[] into) {
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
    private static List<int[]> served(List<Station> c) {
        List<Integer> served = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).id() != null) {
                served.add(i);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < served.size() - 1; i++) {
            pairs.add(new int[] {served.get(i), served.get(i + 1)});
        }
        return pairs;
    }

    private static String id(List<Station> c, int station) {
        return c.get(station).id();
    }

    private static double meters(List<Station> c, int a, int b) {
        return Math.abs(c.get(b).km() - c.get(a).km()) * 1000;
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
}
