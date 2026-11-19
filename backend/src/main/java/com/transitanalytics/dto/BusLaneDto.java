package com.transitanalytics.dto;

import java.time.Instant;
import java.util.List;

/** Bus lanes: where they are, and how fast buses go on them compared with elsewhere. */
public final class BusLaneDto {

    private BusLaneDto() {
    }

    /**
     * The bus lanes for maps.
     *
     * @param laneKm    length of bus lane, each direction's lane counted
     * @param sections  street sections (OpenStreetMap ways) with a bus lane
     * @param official  the length the city gives, for comparison; null when not set
     * @param lanes     each section
     * @param stretches sections grouped by street and direction of travel, as in the report
     */
    public record Lanes(String source, Instant fetchedAt, double laneKm, int sections, Official official,
                        List<Lane> lanes, List<Stretch> stretches) {
    }

    /** The length of bus lanes the city gives, where and when. */
    public record Official(double km, String date, String source, String url) {
    }

    /**
     * A street section with a bus lane: {@code forward} lanes in the direction of {@code points} ([lat, lon]),
     * {@code backward} against it.
     *
     * @param bothWays buses have a lane the other way too: on this section, or on the other carriageway alongside
     */
    public record Lane(long id, String name, int forward, int backward, boolean bothWays, List<double[]> points) {
    }

    /**
     * A street's bus lanes in one direction.
     *
     * @param heading northbound, eastbound, southbound or westbound
     * @param paths   its sections, [lat, lon] in the direction of travel
     */
    public record Stretch(int id, String name, String heading, double km, List<List<double[]>> paths) {
    }

    /**
     * Speeds and delays on bus lanes and elsewhere, from recorded positions.
     *
     * @param days       days with recordings in the window
     * @param onLanes    every bus on a bus lane
     * @param sameStreetsWithoutLane buses on the streets with bus lanes, in the direction without one
     * @param elsewhere  every bus in the city not on a bus lane
     * @param hours      by hour of the day, weekdays
     * @param stretches  per stretch with recordings, the most driven first
     */
    public record Report(Instant from, Instant to, int days, Figures onLanes, Figures sameStreetsWithoutLane,
                         Figures elsewhere, List<Hour> hours,
                         List<StretchFigures> stretches) {
    }

    /**
     * Figures for a set of buses' movements. Speeds include stops, as passengers ride them; delay change is the
     * delay buses gained (negative: made up) per km driven. Peak and off-peak: weekdays only.
     *
     * @param km            km driven (recorded)
     * @param peakSlowdown  how much slower at peak than off-peak, %
     */
    public record Figures(double km, Double speed, Double peakSpeed, Double offPeakSpeed, Double delayPerKm,
                          Double peakDelayPerKm, Double offPeakDelayPerKm, Double peakSlowdown) {
    }

    /** One weekday hour: speeds (km/h) and delay change (s/km), on bus lanes and elsewhere. */
    public record Hour(int hour, Double laneSpeed, Double otherSpeed, Double laneDelayPerKm,
                       Double otherDelayPerKm, double laneKm, double otherKm) {
    }

    /**
     * One stretch's figures.
     *
     * @param km     length of the stretch
     * @param lines    the lines recorded on it
     * @param opposite the same street the other way, where it has no bus lane; null when it has one there too
     */
    public record StretchFigures(int id, String name, String heading, double km, List<String> lines,
                                 Figures figures, Figures opposite) {
    }

    /**
     * How much of a line's route runs on bus lanes, per direction (its main route).
     *
     * @param onLanes the parts on bus lanes, [lat, lon] in order
     */
    public record LineShare(int direction, String from, String to, double km, double laneKm, double percent,
                            List<List<double[]>> onLanes) {
    }
}
