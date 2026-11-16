package com.transitanalytics.gtfs;

/** The GTFS feed could not be loaded because it is incomplete or contains invalid values. */
public class GtfsFeedException extends RuntimeException {

    public GtfsFeedException(String message) {
        super(message);
    }

    public GtfsFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
