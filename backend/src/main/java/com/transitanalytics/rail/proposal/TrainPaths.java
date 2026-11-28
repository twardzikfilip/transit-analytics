package com.transitanalytics.rail.proposal;

import com.transitanalytics.geo.Geo;
import com.transitanalytics.rail.proposal.ProposalScheduler.Pass;
import com.transitanalytics.rail.proposal.ProposalScheduler.Segment;
import com.transitanalytics.data.RailTrains.Call;
import com.transitanalytics.data.RailTrains.Train;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Where today's trains run on the proposal's railways: when each is at each station, and on which stretches. */
final class TrainPaths {

    /** A station of the network at most this far from a train's track is on its way. */
    static final double ON_TRACK_METERS = 150;
    /** A call at most this far along the track from a station of the network is at it. */
    private static final double AT_CALL_METERS = 500;

    /**
     * A train at a station of the network: when it gets there and when it leaves (the same if it passes).
     *
     * @param calls whether it stops there (otherwise it passes, at a time worked out between its calls)
     */
    record At(String station, double along, int arrive, int depart, boolean calls) {
    }

    /** A train between two neighbouring stations of a railway of the network. */
    record Stretch(Segment segment, At from, At to) {

        Pass pass(long train) {
            return new Pass(segment, from.depart(), to.arrive(), (int) train);
        }
    }

    private final Map<String, double[]> stations;
    private final Map<String, String> names;
    private final Map<String, Set<String>> neighbours;

    TrainPaths(ProposalNetwork network) {
        this.stations = network.stationPoints();
        this.names = network.stationNames();
        this.neighbours = network.neighbours();
    }

    /** A train's stretches on the network, in order; none when it doesn't run on it. */
    List<Stretch> of(Train train) {
        return stretches(along(train.track(), train.calls()));
    }

    /**
     * When a train is at each station of the network on its way, in order: at the stations it calls at, its times;
     * at those it passes, as far between its calls before and after as the station lies between them on its track.
     * Without its track, only the stations it calls at.
     */
    List<At> along(List<double[]> shape, List<Call> calls) {
        if (shape == null || shape.size() < 2) {
            List<At> at = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                Call c = calls.get(i);
                if (names.containsKey(c.station())) {
                    at.add(new At(names.get(c.station()), i, c.arrival(), c.departure(), true));
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
                    at(name, along(p, xy, chain, nearest), calls, callAlong).ifPresent(found::add);
                    nearest = -1;
                    nearestMeters = Double.MAX_VALUE;
                }
            }
        });
        found.sort(Comparator.comparingDouble(At::along));
        return found;
    }

    private Optional<At> at(String station, double along, List<Call> calls, double[] callAlong) {
        for (int i = 0; i < calls.size(); i++) {
            if (station.equals(names.get(calls.get(i).station())) && Math.abs(callAlong[i] - along) < AT_CALL_METERS) {
                return Optional.of(new At(station, along, calls.get(i).arrival(), calls.get(i).departure(), true));
            }
        }
        for (int i = 0; i < calls.size() - 1; i++) {
            if (callAlong[i] <= along && along <= callAlong[i + 1] && callAlong[i + 1] > callAlong[i]) {
                double k = (along - callAlong[i]) / (callAlong[i + 1] - callAlong[i]);
                int time = (int) Math.round(calls.get(i).departure()
                        + k * (calls.get(i + 1).arrival() - calls.get(i).departure()));
                return Optional.of(new At(station, along, time, time, false));
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
    List<Stretch> stretches(List<At> at) {
        List<Set<String>> candidates = new ArrayList<>();
        for (int i = 0; i < at.size() - 1; i++) {
            candidates.add(neighbours.getOrDefault(at.get(i).station() + "|" + at.get(i + 1).station(), Set.of()));
        }
        List<Stretch> stretches = new ArrayList<>();
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
                stretches.add(new Stretch(new Segment(railways.iterator().next(), a.station(), b.station()), a, b));
            }
        }
        return stretches;
    }
}
