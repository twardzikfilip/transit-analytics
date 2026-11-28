package com.transitanalytics.rail.proposal;

import com.transitanalytics.dto.CityRailProposalDto;
import com.transitanalytics.rail.proposal.ProposalNetwork.RouteDef;
import com.transitanalytics.rail.proposal.ProposalNetwork.Station;
import com.transitanalytics.rail.proposal.ProposalNetwork.Stop;
import com.transitanalytics.rail.proposal.ProposalScheduler.Run;
import com.transitanalytics.rail.proposal.ProposalScheduler.Timetable;
import com.transitanalytics.rail.proposal.ProposalScheduler.Trip;
import com.transitanalytics.data.RailTrains.Train;
import com.transitanalytics.rail.proposal.TrainPaths.At;
import com.transitanalytics.rail.proposal.TrainPaths.Stretch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.transitanalytics.rail.proposal.ProposalNetwork.PASSED;

/** A route as the page shows it: its figures, and both ways' timetables beside today's trains on its way. */
final class ProposalTimetables {

    /** A train passing a station, in a timetable. */
    static final String PASSES = "|";

    /** A column of a timetable, and when it would leave the route's first station (to put the columns in order). */
    private record Column(int key, CityRailProposalDto.Trip trip) {
    }

    private final ProposalNetwork network;
    /** Today's trains on the network, with their stretches on it. */
    private final Map<Train, List<Stretch>> today;

    ProposalTimetables(ProposalNetwork network, Map<Train, List<Stretch>> today) {
        this.network = network;
        this.today = today;
    }

    CityRailProposalDto.Route route(RouteDef route, Timetable t, ProposalScheduler scheduler) {
        List<Stop> stops = network.stops(route, true);
        List<CityRailProposalDto.RouteStation> stations = new ArrayList<>();
        Set<String> passed = new HashSet<>();
        double km = 0;
        int calledAt = 0;
        for (int i = 0; i < stops.size(); i++) {
            Station s = stops.get(i).station();
            km += i == 0 ? 0 : Math.abs(s.km() - stops.get(i - 1).station().km());
            if (PASSED.equals(s.kind())) {
                passed.add(s.name());
            } else {
                calledAt++;
            }
            stations.add(new CityRailProposalDto.RouteStation(s.name(), s.kind(), round(km)));
        }
        int closest = ProposalScheduler.NONE;
        int conflicts = 0;
        List<CityRailProposalDto.Trip> out = new ArrayList<>();
        List<CityRailProposalDto.Trip> back = new ArrayList<>();
        for (boolean outward : new boolean[] {true, false}) {
            Run run = outward ? t.out() : t.back();
            List<Column> columns = new ArrayList<>();
            for (Trip trip : outward ? t.outs() : t.backs()) {
                int c = scheduler.closest(run, trip.start(), trip.train());
                closest = Math.min(closest, c);
                boolean conflict = c < ProposalScheduler.HEADWAY;
                conflicts += conflict ? 1 : 0;
                List<String> times = new ArrayList<>();
                for (int i = 0; i < run.stations().size(); i++) {
                    boolean last = i == run.stations().size() - 1;
                    times.add(!last && i > 0 && passed.contains(run.stations().get(i)) ? PASSES
                            : hhmm(trip.start() + (last ? run.arrive()[i] : run.depart()[i])));
                }
                columns.add(new Column(trip.start(), new CityRailProposalDto.Trip("city", route.id(), times, conflict)));
            }
            today.forEach((train, stretches) -> columns.addAll(columns(run, train, stretches)));
            columns.sort(Comparator.comparingInt(Column::key));
            columns.forEach(c -> (outward ? out : back).add(c.trip()));
        }
        return new CityRailProposalDto.Route(route.id(), stations, network.points(route), round(km),
                round(km / Math.max(1, calledAt - 1)), t.out().duration() / 60, t.back().duration() / 60,
                Math.min(t.outs().size(), t.backs().size()),
                Math.max(maxInterval(t.outs()), maxInterval(t.backs())) / 60, t.vehicles(),
                t.minTurnaround() == ProposalScheduler.NONE ? 0 : t.minTurnaround() / 60, t.maxTurnaround() / 60,
                closest == ProposalScheduler.NONE ? null : closest / 60, conflicts, out, back);
    }

    /**
     * A train of today on a route's way: a column for each run of stretches it goes along the way, with its times at
     * the stations of the way on it ("|" where it passes).
     */
    private static List<Column> columns(Run run, Train train, List<Stretch> stretches) {
        List<Column> columns = new ArrayList<>();
        int k = 0;
        while (k < stretches.size()) {
            int first = run.segments().indexOf(stretches.get(k).segment());
            if (first < 0) {
                k++;
                continue;
            }
            At[] at = new At[run.stations().size()];
            at[first] = stretches.get(k).from();
            int j = first;
            while (k < stretches.size() && j < run.segments().size()
                    && stretches.get(k).segment().equals(run.segments().get(j))) {
                at[j + 1] = stretches.get(k).to();
                j++;
                k++;
            }
            List<String> times = new ArrayList<>();
            for (int i = 0; i < at.length; i++) {
                times.add(at[i] == null ? null : !at[i].calls() ? PASSES
                        : hhmm(i == j ? at[i].arrive() : at[i].depart()));
            }
            columns.add(new Column(at[first].depart() - run.depart()[first], new CityRailProposalDto.Trip(
                    train.agency(), train.label(), times, false)));
        }
        return columns;
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
}
