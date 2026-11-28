package com.transitanalytics.trips;

/**
 * How long a bus halted at a stop, from the steps between its consecutive reports near it: a trip's stops in its
 * detail, and every stop over a time window (the Home page's longest dwell).
 */
public final class Dwell {

    /**
     * A bus within this distance of a stop is at it: the timetable has the stop's pole, and buses halt in a bay or
     * across a junction from it.
     */
    public static final double AT_STOP_METRES = 50;
    /**
     * Reports come about every 10 s, longer than a short halt: a bus pulling in, halting and pulling out still moves a
     * few metres between two reports. So a step near the stop slower than this is a halt…
     */
    static final double HALT_KMH = 7;
    /** …and the bus went past without stopping only when every step near the stop was at least this fast. */
    static final double PASSED_KMH = 12;

    private Dwell() {
    }

    /**
     * Whether a step between two reports of a bus tells how it moved: some time passed, at most
     * {@link TripRunService#REPORT_GAP}, and no faster than {@link TripRunService#MAX_KMH} (else a GPS jump).
     */
    public static boolean usable(double metres, double seconds) {
        return seconds > 0 && seconds <= TripRunService.REPORT_GAP.getSeconds()
                && metres / seconds * 3.6 <= TripRunService.MAX_KMH;
    }

    /** One bus at one stop: its usable steps there, added one by one. */
    public static final class Visit {
        private double halted;
        private boolean slow;
        private boolean unclear;
        private boolean any;

        public void add(double metres, double seconds) {
            any = true;
            double kmh = metres / seconds * 3.6;
            if (kmh < HALT_KMH) {
                slow = true;
                halted += seconds;
            } else if (kmh < PASSED_KMH) {
                unclear = true;
            }
        }

        /**
         * The time of its steps slower than {@link #HALT_KMH}, to about 10 s (at least 1 when it halted); 0 when every
         * step was at least {@link #PASSED_KMH} (it went past); null when that can't be told: no step, or one in
         * between.
         */
        public Integer seconds() {
            if (slow) {
                return Math.max(1, (int) Math.round(halted));
            }
            return !any || unclear ? null : 0;
        }
    }
}
