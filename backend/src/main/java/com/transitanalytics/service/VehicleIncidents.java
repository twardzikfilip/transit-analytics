package com.transitanalytics.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Whether a vehicle that stopped reporting on its brigade left it while the brigade was scheduled to run. Vehicles may
 * leave at a break (a gap of {@link #BREAK} or more between runs) or at the end of the brigade's day; anywhere else, a
 * last report means the vehicle left the brigade mid-service. Times are seconds of the service day, as in GTFS.
 */
final class VehicleIncidents {

    /** A gap between two runs at least this long is a break, where vehicles may change. */
    static final int BREAK = 30 * 60;
    /** Slack around a run's end: the last report comes a little before the vehicle reaches the terminus. */
    static final int SLACK = 15 * 60;
    /** Recording must go on at least this long after the last report, else it may be a gap in recording. */
    static final Duration RECORDING_AFTER = Duration.ofMinutes(15);

    private VehicleIncidents() {
    }

    /** One scheduled run of a brigade. */
    record Run(int start, int end) {
    }

    /**
     * What the vehicle missed, when its last report was outside the schedule's gaps.
     *
     * @param until      end of the block of runs it left (the next break, or the brigade's day end)
     * @param end        end of the brigade's day
     * @param missedRuns runs of that block not completed
     */
    record Missed(int until, int end, int missedRuns) {
    }

    /**
     * @param runs      the brigade's runs that day, in start order
     * @param scheduled the time the vehicle was running to at its last report: report time minus its delay
     * @return what it missed, or null when it left at a break, at or near the end of its day, or before its first run
     */
    static Missed missed(List<Run> runs, int scheduled) {
        if (runs.isEmpty()) {
            return null;
        }
        int end = runs.stream().mapToInt(Run::end).max().orElseThrow();
        if (scheduled >= end - SLACK || scheduled < runs.get(0).start()) {
            return null;
        }
        // The block it was in ends at the first break after its scheduled time.
        int until = end;
        for (int i = 0; i + 1 < runs.size(); i++) {
            Run run = runs.get(i);
            Run next = runs.get(i + 1);
            boolean isBreak = next.start() - run.end() >= BREAK;
            if (!isBreak) {
                continue;
            }
            if (scheduled >= run.end() - SLACK && scheduled < next.start()) {
                return null; // left at a break
            }
            if (run.end() > scheduled) {
                until = run.end();
                break;
            }
        }
        int limit = until;
        int missedRuns = (int) runs.stream().filter(r -> r.end() > scheduled + SLACK && r.end() <= limit).count();
        return new Missed(until, end, Math.max(1, missedRuns));
    }

    /** Whether recording went on long enough after {@code lastSeen} to tell a vehicle leaving from a recording gap. */
    static boolean recordedAfter(Instant lastSeen, Instant lastPollOfDay) {
        return !lastPollOfDay.isBefore(lastSeen.plus(RECORDING_AFTER));
    }
}
