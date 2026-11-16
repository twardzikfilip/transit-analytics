package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Kind of line, stored and sent as lowercase text ("urban", "suburban", "night_urban", "regional"). */
public enum RouteSubtype {
    URBAN, SUBURBAN, NIGHT_URBAN, REGIONAL;

    private static final Pattern NIGHT = Pattern.compile("(?i)N\\d+");
    /** A number of 100 or more. */
    private static final Pattern SUBURBAN_NUMBER = Pattern.compile("0*[1-9]\\d{2,}");

    /**
     * The default subtype for a line name: N1, N2, … are night lines, numbers from 100 up are suburban, everything
     * else is urban. Regional is only ever set by hand. Keep in sync with V10__route_subtype.sql and the GTFS import.
     */
    public static RouteSubtype classify(String name) {
        if (name == null) {
            return URBAN;
        }
        String n = name.strip();
        if (NIGHT.matcher(n).matches()) {
            return NIGHT_URBAN;
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

    /** Maps the enum to the lowercase column values. */
    @jakarta.persistence.Converter(autoApply = true)
    public static class Converter implements AttributeConverter<RouteSubtype, String> {
        @Override
        public String convertToDatabaseColumn(RouteSubtype subtype) {
            return subtype == null ? null : subtype.value();
        }

        @Override
        public RouteSubtype convertToEntityAttribute(String value) {
            return value == null ? null : of(value);
        }
    }
}
