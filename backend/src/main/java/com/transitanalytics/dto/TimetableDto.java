package com.transitanalytics.dto;

import java.util.List;

/** The Stops and Stop times pages: timetable rows, a page at a time. */
public final class TimetableDto {

    private TimetableDto() {
    }

    /** @param page zero-based */
    public record Page<T>(List<T> content, Meta page) {

        public static <T> Page<T> of(List<T> content, int page, int size, long total) {
            return new Page<>(content, new Meta(size, page, total, (int) Math.max(1, (total + size - 1) / size)));
        }
    }

    public record Meta(int size, int number, long totalElements, int totalPages) {
    }

    /** @param feed the timetable it is from: bialystok (BKM's) or another operator's */
    public record Stop(long id, String feed, String code, String name, Double latitude, Double longitude,
                       String zoneId) {
    }

    /**
     * When a trip calls at a stop. Times are "HH:MM:SS" and may pass 24:00:00.
     *
     * @param serviceId  which days the trip runs
     * @param pickupType GTFS: 0 regular, 1 none, 2 phone the agency, 3 ask the driver; dropOffType the same
     */
    public record StopTime(long id, long tripId, String tripGtfsId, String tripHeadsign, String serviceId, String routeName, long stopId,
                           String stopName, int stopSequence, String arrivalTime, String departureTime,
                           String stopHeadsign, Short pickupType, Short dropOffType) {
    }
}
