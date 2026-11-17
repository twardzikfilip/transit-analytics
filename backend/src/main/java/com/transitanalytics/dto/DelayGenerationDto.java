package com.transitanalytics.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Delay generation: how much delay buses gain (or recover) between consecutive stops, from recorded positions. The
 * delay is the feed's, taken just after a bus leaves each stop; the change between two stops is the delay generated
 * there (the drive and the dwell at the second stop).
 */
public final class DelayGenerationDto {

    private DelayGenerationDto() {
    }

    /** A part of the day; the first one is the whole day. */
    public record Band(String key, String label) {
    }

    /**
     * The whole network.
     *
     * @param days       service days with recordings in the window; per-day figures are over these
     * @param runs       runs measured (a bus on one trip, from stop to stop)
     * @param stretches  directed stretches between consecutive stops, all lines together
     * @param lines      each line's delay gained per run
     */
    public record Report(String window, Instant from, Instant to, int days, int runs, List<Band> bands,
                         List<Stretch> stretches, List<LineSummary> lines) {
    }

    /**
     * One direction between two consecutive stops, all lines together.
     *
     * @param runs         buses measured on it, per band (aligned with {@link Report#bands()})
     * @param meanSeconds  their average change in delay per band (positive: delay generated), null without runs
     */
    public record Stretch(String from, String to, double fromLat, double fromLon, double toLat, double toLon,
                          List<String> lines, List<Integer> runs, List<Double> meanSeconds) {
    }

    /**
     * @param meanGainSeconds         average change in delay from a run's first stop to its last
     * @param gainingShare            share of runs gaining 3 minutes or more
     * @param generatedMinutesPerDay  delay gained by all its runs together, per recorded day
     * @param worstFrom               the stretch generating the most delay altogether (null when none)
     */
    public record LineSummary(String line, int runs, double meanGainSeconds, int medianGainSeconds,
                              double gainingShare, double generatedMinutesPerDay, String worstFrom, String worstTo,
                              Double worstMeanSeconds) {
    }

    /** One line: per direction, where and when delay is generated, with example runs. */
    public record LineReport(String window, Instant from, Instant to, String line, int days,
                             List<Direction> directions, List<BrigadeGain> brigades, List<Example> examples) {
    }

    /**
     * Runs towards one headsign.
     *
     * @param stretches  in route order
     * @param exampleIds the runs that gained the most delay (ids in {@link LineReport#examples()})
     */
    public record Direction(String headsign, int runs, double meanGainSeconds, List<LineStretch> stretches,
                            List<Integer> exampleIds) {
    }

    /**
     * A stretch of one direction of a line.
     *
     * @param meanSeconds   average change in delay (positive: generated)
     * @param gainingShare  share of runs gaining a minute or more here
     * @param delayBefore   average delay on leaving {@code from}
     * @param byHour        average change by hour of the day (local time, index 0–23), null with under 3 runs
     * @param exampleIds    runs that gained the most here (a minute or more)
     */
    public record LineStretch(String from, String to, int fromSeq, int runs, double meanSeconds, int medianSeconds,
                              int p90Seconds, double gainingShare, double delayBefore, List<Double> byHour,
                              List<Integer> exampleIds) {
    }

    /** A brigade's runs on the line: how much delay they gain. */
    public record BrigadeGain(String brigade, int runs, double meanGainSeconds, int maxGainSeconds) {
    }

    /** A run: the delay on leaving each stop. */
    public record Example(int id, LocalDate day, String vehicle, String brigade, String headsign, int gainSeconds,
                          List<ExampleStop> stops) {
    }

    /** Leaving a stop: when, and the delay then (the scheduled time is {@code left} minus the delay). */
    public record ExampleStop(String stop, int seq, Instant left, int delaySeconds) {
    }
}
