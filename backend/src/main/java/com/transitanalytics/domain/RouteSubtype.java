package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Kind of route, stored and sent as lowercase text ("urban", "suburban", "night_urban", "regional"). */
public enum RouteSubtype {
    URBAN, SUBURBAN, NIGHT_URBAN, REGIONAL;

    private static final Pattern NIGHT = Pattern.compile("(?i)N\\d+");
    /** 400–499 and 700: regional routes, run by other operators to the towns of the region. */
    private static final Pattern REGIONAL_NUMBER = Pattern.compile("0*(4\\d{2}|700)");
    /** A number of 100 or more. */
    private static final Pattern SUBURBAN_NUMBER = Pattern.compile("0*[1-9]\\d{2,}");

    /**
     * The default subtype for a route name: N1, N2, … are night routes, 400–499 and 700 regional, other numbers from
     * 100 up suburban, everything else urban. Keep in sync with V13__regional_line_700.sql and the GTFS import.
     */
    public static RouteSubtype classify(String name) {
        if (name == null) {
            return URBAN;
        }
        String n = name.strip();
        if (NIGHT.matcher(n).matches()) {
            return NIGHT_URBAN;
        }
        if (REGIONAL_NUMBER.matcher(n).matches()) {
            return REGIONAL;
        }
        if (SUBURBAN_NUMBER.matcher(n).matches()) {
            return SUBURBAN;
        }
        return URBAN;
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RouteSubtype of(String value) {
        return Arrays.stream(values()).filter(s -> s.value().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown route subtype: " + value));
    }
}
