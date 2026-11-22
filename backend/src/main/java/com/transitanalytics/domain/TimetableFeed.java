package com.transitanalytics.domain;

/**
 * The GTFS feed a timetable row came from (the {@code feed} column). Feeds reuse each other's ids, so an id is unique
 * only within its feed.
 */
public final class TimetableFeed {

    /** The main feed: Białystok's timetable (komunikacja.bialystok.pl). Other feeds are keys under app.gtfs.extra. */
    public static final String MAIN = "bialystok";

    private TimetableFeed() {
    }
}
