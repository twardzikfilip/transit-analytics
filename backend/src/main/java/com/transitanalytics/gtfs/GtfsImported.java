package com.transitanalytics.gtfs;

import java.time.Instant;

/** Published when a timetable feed has been imported. */
public record GtfsImported(String feed, Instant at) {
}
