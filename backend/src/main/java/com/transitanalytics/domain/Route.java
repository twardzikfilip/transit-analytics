package com.transitanalytics.domain;

/**
 * A timetable route (GTFS routes.txt) with what the backend classified about it.
 *
 * @param agency    the agency's name; null when the timetable names none
 * @param agencyUrl its website
 */
public record Route(long id, String feed, String gtfsId, String shortName, String longName, String color,
                    String textColor, String url, RouteSubtype subtype, RouteGeometry geometry,
                    RouteCapacity capacityClass, String operatingAgency, String agency, String agencyUrl) {

    /** What riders see: the short name, or the long name when there is none. */
    public String displayName() {
        return shortName != null && !shortName.isBlank() ? shortName : longName;
    }
}
