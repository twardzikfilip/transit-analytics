package com.transitanalytics.gtfs;

import java.util.List;

/**
 * A GTFS file we load, and the columns we read from it. Each file is first copied into a temporary staging table
 * named {@code gtfs_<name>} with one text column per GTFS column; other columns in the file are ignored.
 */
enum GtfsFile {

    AGENCY("agency.txt", true, List.of("agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang",
            "agency_phone", "agency_fare_url", "agency_email")),
    ROUTES("routes.txt", true, List.of("route_id", "agency_id", "route_short_name", "route_long_name", "route_desc",
            "route_type", "route_url", "route_color", "route_text_color", "route_sort_order")),
    STOPS("stops.txt", true, List.of("stop_id", "stop_code", "stop_name", "stop_desc", "stop_lat", "stop_lon",
            "zone_id", "stop_url", "location_type", "parent_station", "wheelchair_boarding", "platform_code")),
    TRIPS("trips.txt", true, List.of("route_id", "service_id", "trip_id", "trip_headsign", "trip_short_name",
            "direction_id", "block_id", "shape_id", "wheelchair_accessible", "bikes_allowed")),
    STOP_TIMES("stop_times.txt", true, List.of("trip_id", "arrival_time", "departure_time", "stop_id",
            "stop_sequence", "stop_headsign", "pickup_type", "drop_off_type", "shape_dist_traveled", "timepoint")),
    CALENDAR_DATES("calendar_dates.txt", false, List.of("service_id", "date", "exception_type")),
    SHAPES("shapes.txt", false, List.of("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence",
            "shape_dist_traveled"));

    final String fileName;
    final boolean required;
    final List<String> columns;

    GtfsFile(String fileName, boolean required, List<String> columns) {
        this.fileName = fileName;
        this.required = required;
        this.columns = columns;
    }

    String stagingTable() {
        return "gtfs_" + name().toLowerCase();
    }

    String dropStagingTable() {
        return "DROP TABLE IF EXISTS " + stagingTable();
    }

    String createStagingTable() {
        return "CREATE TEMP TABLE " + stagingTable() + " ("
                + String.join(" text, ", columns) + " text) ON COMMIT DROP";
    }

    String copyCommand() {
        return "COPY " + stagingTable() + " (" + String.join(", ", columns) + ") FROM STDIN WITH (FORMAT csv)";
    }
}
