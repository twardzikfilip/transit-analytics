package com.transitanalytics.domain;

/**
 * The GTFS feed a timetable row came from (the {@code feed} column). Feeds reuse each other's ids, so an id is unique
 * only within its feed.
 */
public final class TimetableFeed {

    /** The main feed: Białystok's timetable (komunikacja.bialystok.pl). Other feeds are keys under app.gtfs.extra. */
    public static final String MAIN = "bialystok";

    /**
     * The trains, Polregio's and PKP Intercity's (a key under app.gtfs.extra): for the City Rail page only. The pages about buses leave its
     * rows out.
     */
    public static final String RAIL = "rail";

    private TimetableFeed() {
    }
}
