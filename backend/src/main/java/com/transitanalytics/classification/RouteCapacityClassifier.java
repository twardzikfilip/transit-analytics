package com.transitanalytics.classification;

import com.transitanalytics.domain.RouteCapacity;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A line's capacity class from its departures and its MEGA buses, by the rules in {@code classification/routes.yml}. */
public final class RouteCapacityClassifier {

    /**
     * What the rules look at.
     *
     * @param megaWeekday       share of the line's reports made by MEGA buses on weekdays; null when not recorded
     * @param megaSaturday      …on Saturdays; null when the line wasn't recorded then
     * @param megaSunday        …on Sundays (and holidays)
     * @param peakHeadway       minutes between departures at peak, per direction, averaged; null without peak trips
     * @param averageHeadway    minutes between departures from the first to the last of the day, averaged
     * @param tripsPerDirection departures a day per direction, averaged
     * @param headwayCv         how uneven the gaps are (coefficient of variation; 0 = even); null with too few trips
     */
    public record Inputs(Double megaWeekday, Double megaSaturday, Double megaSunday, Double peakHeadway,
                         Double averageHeadway, double tripsPerDirection, Double headwayCv) {
    }

    private final RouteClassProperties.Capacity rules;
    /** Peak windows in seconds after midnight, [from, to). */
    private final List<int[]> peak;

    public RouteCapacityClassifier(RouteClassProperties.Capacity rules) {
        this.rules = rules;
        this.peak = rules.peak().stream().map(RouteCapacityClassifier::window).toList();
    }

    private static int[] window(String spec) {
        String[] parts = spec.split("-");
        return new int[] {LocalTime.parse(parts[0].strip()).toSecondOfDay(), LocalTime.parse(parts[1].strip())
                .toSecondOfDay()};
    }

    /** The capacity class: the first rule that holds, else the fallback. */
    public RouteCapacity classify(Inputs in) {
        for (RouteClassProperties.Rule rule : rules.rules()) {
            if (holds(rule, in)) {
                return rule.capacityClass();
            }
        }
        return rules.fallback();
    }

    private boolean holds(RouteClassProperties.Rule r, Inputs in) {
        return mega(r.mega(), in)
                && (r.peakHeadwayUnder() == null || in.peakHeadway() != null && in.peakHeadway() < r.peakHeadwayUnder())
                && (r.averageHeadwayUnder() == null
                || in.averageHeadway() != null && in.averageHeadway() < r.averageHeadwayUnder())
                && (r.minTripsPerDirection() == null || in.tripsPerDirection() >= r.minTripsPerDirection())
                && (r.maxHeadwayCv() == null || in.headwayCv() != null && in.headwayCv() <= r.maxHeadwayCv());
    }

    private boolean mega(RouteClassProperties.Mega mega, Inputs in) {
        if (mega == null || mega == RouteClassProperties.Mega.ANY) {
            return true;
        }
        boolean some = in.megaWeekday() != null && in.megaWeekday() >= rules.megaSomeShare();
        return switch (mega) {
            case EVERY_DAY -> in.megaWeekday() != null && everyDay(in.megaWeekday()) && everyDay(in.megaSaturday())
                    && everyDay(in.megaSunday());
            case SOME -> some;
            case NONE -> !some;
            case ANY -> true;
        };
    }

    /** A day type the line wasn't recorded on (it doesn't run then) doesn't count against it. */
    private boolean everyDay(Double share) {
        return share == null || share >= rules.megaEveryDayShare();
    }

    /**
     * The timetable inputs from a day's departures (seconds after midnight) per direction; the MEGA shares are given.
     */
    public Inputs inputs(Collection<List<Integer>> departuresByDirection, Double megaWeekday, Double megaSaturday,
                         Double megaSunday) {
        List<Double> peakHeadways = new ArrayList<>();
        List<Double> averageHeadways = new ArrayList<>();
        int peakMinutes = peak.stream().mapToInt(w -> (w[1] - w[0]) / 60).sum();
        double cvSum = 0;
        int gaps = 0;
        int trips = 0;
        for (List<Integer> unsorted : departuresByDirection) {
            List<Integer> times = unsorted.stream().sorted().toList();
            trips += times.size();
            long atPeak = times.stream().filter(t -> peak.stream().anyMatch(w -> t >= w[0] && t < w[1])).count();
            if (atPeak > 0) {
                peakHeadways.add((double) peakMinutes / atPeak);
            }
            if (times.size() > 1) {
                averageHeadways.add((times.get(times.size() - 1) - times.get(0)) / 60.0 / (times.size() - 1));
            }
            List<Integer> g = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                if (times.get(i) > times.get(i - 1)) {
                    g.add(times.get(i) - times.get(i - 1));
                }
            }
            if (g.size() >= 2) {
                double mean = g.stream().mapToInt(Integer::intValue).average().orElse(0);
                double variance = g.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
                cvSum += Math.sqrt(variance) / mean * g.size();
                gaps += g.size();
            }
        }
        int directions = Math.max(1, departuresByDirection.size());
        return new Inputs(megaWeekday, megaSaturday, megaSunday, average(peakHeadways), average(averageHeadways),
                (double) trips / directions, gaps == 0 ? null : cvSum / gaps);
    }

    private static Double average(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
