package com.transitanalytics.service;

import com.transitanalytics.service.TripAnomalies.Report;
import com.transitanalytics.service.TripAnomalies.ScheduledTrip;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.transitanalytics.service.TimetableMath.LINE_ORDER;

/**
 * Part of {@link RecordedBrigades}: learns the brigades of a timetable that doesn't name them from the days recorded.
 * Each report is matched to the trip of its line it was running (by time, as {@link TripMatching#match}); a trip was
 * run that day by the bus and live brigade number with most reports on it.
 * <p>
 * The live numbers repeat from line to line ("1" on line 400 and "1" on line 414 are different buses), but a bus runs
 * some lines together under one number (414, 415 and 416). So a brigade is a number on a group of lines: the lines
 * one bus ran under that number on some day, joined. It is named after the group's first line: "414-1".
 * <p>
 * These timetables give every date its own trips (trip 143292 runs on 15 November only), so what is learnt is the
 * brigade of a {@link Run}: the line's trip from one stop to another at a time of day, on a type of day. It belongs to
 * the brigade that ran it on most days, and so does the trip of any date (also future ones) that is that run.
 */
final class BrigadeLearning {

    /** A bus counts as running a trip with at least this many reports on it: fewer is a moment of a wrong brigade. */
    static final int MIN_REPORTS = 3;

    /** A live brigade number on a line, as the reports give them. */
    record LiveBrigade(String line, String live) {
    }

    /**
     * A trip as it recurs from day to day: its line, first and last stop, departure (seconds after the service day's
     * start) and type of day (weekday, saturday, sunday).
     */
    record Run(String dayType, String line, String from, String to, long departure) {

        static Run of(LocalDate day, ScheduledTrip trip) {
            DayOfWeek weekday = day.getDayOfWeek();
            String type = weekday == DayOfWeek.SATURDAY ? "saturday" : weekday == DayOfWeek.SUNDAY ? "sunday" : "weekday";
            return new Run(type, trip.line(), trip.stops()[0], trip.stops()[trip.size() - 1],
                    Duration.between(ServiceDay.start(day), trip.departure()).toSeconds());
        }
    }

    /** The brigade a run belongs to, and on how many days that brigade's bus ran it. */
    record Assignment(String brigade, int days) {
    }

    /**
     * What was learnt.
     *
     * @param brigades the brigade of each live number on a line
     * @param runs     the brigade of each run seen run at least once
     */
    record Learnt(Map<LiveBrigade, String> brigades, Map<Run, Assignment> runs) {

        /** The brigade of a trip on a day; null when its run wasn't seen run. */
        Assignment of(LocalDate day, ScheduledTrip trip) {
            return runs.get(Run.of(day, trip));
        }
    }

    /** A bus on a live brigade number, on one day. */
    private record Duty(long vehicleId, String live) {
    }

    /** By run: on how many days each live number ran it. */
    private final Map<Run, Map<String, Integer>> votes = new HashMap<>();
    /** By live number: the lines run together under it. */
    private final Map<String, LineGroups> groups = new HashMap<>();

    /**
     * Adds a recorded day.
     *
     * @param day     the service day
     * @param trips   the day's trips of the timetable
     * @param reports the day's reports of the operator's buses, by vehicle and time, brigades as the live feed gives
     */
    void add(LocalDate day, List<ScheduledTrip> trips, List<Report> reports) {
        Map<String, List<ScheduledTrip>> byLine = trips.stream().collect(Collectors.groupingBy(ScheduledTrip::line));
        Map<ScheduledTrip, Map<Duty, Integer>> counts = new IdentityHashMap<>();
        for (Report r : TripMatching.withBrigades(reports)) {
            if (r.brigade() == null || r.brigade().isBlank()) {
                continue;
            }
            ScheduledTrip trip = TripMatching.match(r, byLine.get(r.line()));
            if (trip != null) {
                counts.computeIfAbsent(trip, k -> new HashMap<>()).merge(new Duty(r.vehicleId(), r.brigade()), 1,
                        Integer::sum);
            }
        }
        Map<Duty, Set<String>> linesOfDuty = new HashMap<>();
        counts.forEach((trip, byDuty) -> byDuty.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_REPORTS)
                .max(Map.Entry.<Duty, Integer>comparingByValue()
                        .thenComparing(e -> e.getKey().live(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .ifPresent(duty -> {
                    votes.computeIfAbsent(Run.of(day, trip), k -> new HashMap<>()).merge(duty.live(), 1, Integer::sum);
                    linesOfDuty.computeIfAbsent(duty, k -> new TreeSet<>()).add(trip.line());
                }));
        linesOfDuty.forEach((duty, lines) -> groups.computeIfAbsent(duty.live(), k -> new LineGroups()).join(lines));
    }

    /** The brigades learnt from the days added. */
    Learnt learnt() {
        Map<LiveBrigade, String> brigades = new HashMap<>();
        groups.forEach((live, lineGroups) -> lineGroups.firstLines().forEach(
                (line, first) -> brigades.put(new LiveBrigade(line, live), first + "-" + live)));
        Map<Run, Assignment> runs = new HashMap<>();
        votes.forEach((run, byLive) -> {
            // Most days; a tie goes to the lower number, so that the result doesn't depend on the order of a map.
            Map.Entry<String, Integer> top = byLive.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .orElseThrow();
            runs.put(run, new Assignment(brigades.get(new LiveBrigade(run.line(), top.getKey())), top.getValue()));
        });
        return new Learnt(brigades, runs);
    }

    /** Lines joined into groups (union-find). */
    private static final class LineGroups {
        private final Map<String, String> parent = new HashMap<>();

        void join(Set<String> lines) {
            String first = null;
            for (String line : lines) {
                String root = root(line);
                if (first == null) {
                    first = root;
                } else if (!root.equals(first)) {
                    parent.put(root, first);
                }
            }
        }

        private String root(String line) {
            String p = parent.putIfAbsent(line, line);
            if (p == null || p.equals(line)) {
                return line;
            }
            String root = root(p);
            parent.put(line, root);
            return root;
        }

        /** Each line, with the first line (in line order) of its group. */
        Map<String, String> firstLines() {
            Map<String, List<String>> members = new HashMap<>();
            for (String line : new ArrayList<>(parent.keySet())) {
                members.computeIfAbsent(root(line), k -> new ArrayList<>()).add(line);
            }
            Map<String, String> first = new HashMap<>();
            members.values().forEach(group -> {
                String name = group.stream().min(LINE_ORDER).orElseThrow();
                group.forEach(line -> first.put(line, name));
            });
            return first;
        }
    }
}
