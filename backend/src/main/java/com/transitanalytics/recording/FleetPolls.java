package com.transitanalytics.recording;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vehicles running at each poll, worked out from the reports. A vehicle is running at a poll when it reported in the
 * minute up to it: each report covers the time until the vehicle's next report, or a minute, whichever is first. This
 * doesn't depend on how often the feed is polled, nor on vehicles that skip a poll now and then.
 */
final class FleetPolls {

    /** How long one report keeps a vehicle counted as running. */
    static final Duration REPORT_COVERS = Duration.ofMinutes(1);

    /** One poll: vehicles running, reports made, and their delays (summed, for means over any span). */
    record Poll(Instant time, int vehicles, int reports, long delaySum, int delayCount) {
    }

    /**
     * The polls of a span of time, with what the window totals need: the vehicles that reported and the first and
     * last report.
     */
    record Part(List<Poll> polls, Set<Long> vehicles, Instant first, Instant last) {
    }

    /** A report as read: its poll, when the vehicle reported, the vehicle, and its delay (null when unknown). */
    public record Report(long poll, long observed, long vehicle, Integer delay) {
    }

    private FleetPolls() {
    }

    /**
     * The polls from {@code from} on, from reports that start up to a minute earlier (so vehicles that reported just
     * before count as running at its first poll). Times are epoch milliseconds.
     */
    static Part polls(List<Report> reports, Instant from) {
        long start = from.toEpochMilli();
        long covers = REPORT_COVERS.toMillis();
        Map<Long, List<Long>> byVehicle = new HashMap<>();
        Map<Long, int[]> perPoll = new HashMap<>();   // reports, delay count
        Map<Long, Long> delays = new HashMap<>();
        Set<Long> vehicles = new HashSet<>();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Report r : reports) {
            byVehicle.computeIfAbsent(r.vehicle(), k -> new ArrayList<>()).add(r.poll());
            if (r.poll() < start) {
                continue;
            }
            int[] counts = perPoll.computeIfAbsent(r.poll(), k -> new int[2]);
            counts[0]++;
            if (r.delay() != null) {
                counts[1]++;
                delays.merge(r.poll(), (long) r.delay(), Long::sum);
            }
            vehicles.add(r.vehicle());
            first = Math.min(first, r.observed());
            last = Math.max(last, r.observed());
        }
        // Each report starts a span (+1) that ends (-1) at the vehicle's next report or a minute on.
        Map<Long, Integer> change = new HashMap<>();
        for (List<Long> polls : byVehicle.values()) {
            long[] times = polls.stream().mapToLong(Long::longValue).sorted().distinct().toArray();
            for (int i = 0; i < times.length; i++) {
                long stop = i + 1 < times.length ? Math.min(times[i + 1], times[i] + covers) : times[i] + covers;
                change.merge(times[i], 1, Integer::sum);
                change.merge(stop, -1, Integer::sum);
            }
        }
        long[] times = change.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        Map<Long, Integer> running = new HashMap<>();
        int now = 0;
        for (long t : times) {
            now += change.get(t);
            running.put(t, now);
        }
        long[] pollTimes = perPoll.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        List<Poll> polls = new ArrayList<>(pollTimes.length);
        for (long t : pollTimes) {
            int[] counts = perPoll.get(t);
            polls.add(new Poll(Instant.ofEpochMilli(t), running.getOrDefault(t, 0), counts[0],
                    delays.getOrDefault(t, 0L), counts[1]));
        }
        return new Part(polls, vehicles, first == Long.MAX_VALUE ? null : Instant.ofEpochMilli(first),
                last == Long.MIN_VALUE ? null : Instant.ofEpochMilli(last));
    }

    /** Polls in {@code [from, to)} of the parts, in time order. */
    static List<Poll> between(List<Part> parts, Instant from, Instant to) {
        return parts.stream().flatMap(p -> p.polls().stream())
                .filter(p -> !p.time().isBefore(from) && p.time().isBefore(to))
                .sorted(Comparator.comparing(Poll::time)).toList();
    }
}
