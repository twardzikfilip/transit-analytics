package com.transitanalytics.domain;

import java.time.ZoneId;
import java.util.List;

/**
 * The city's time zone (service days, schedules and local times are all in it) and the standard parts of a weekday
 * the pages measure in.
 */
public final class CityTime {

    /** As a constant, for annotations such as {@code @Scheduled(zone = CityTime.ZONE_ID)}. */
    public static final String ZONE_ID = "Europe/Warsaw";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    /** Peak hours on weekdays, "HH:MM-HH:MM": the morning and afternoon rush. */
    public static final List<String> PEAK = List.of("06:30-08:30", "15:00-17:00");
    /** Off-peak daytime hours on weekdays, between and after the peaks. */
    public static final List<String> OFF_PEAK = List.of("08:30-15:00", "17:00-20:00");

    private CityTime() {
    }
}
