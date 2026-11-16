package com.transitanalytics.gtfs;

/** Another import of the same feed is still running. */
public class GtfsImportInProgressException extends RuntimeException {

    public GtfsImportInProgressException(String feed) {
        super("A " + feed + " GTFS import is already running");
    }
}
