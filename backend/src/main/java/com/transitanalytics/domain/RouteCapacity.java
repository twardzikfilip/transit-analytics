package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How much a route carries, from its buses and frequency (rules set in {@code classification/routes.yml}): main,
 * primary, secondary, tertiary or irregular, from the strongest to the weakest.
 */
public enum RouteCapacity {
    MAIN, PRIMARY, SECONDARY, TERTIARY, IRREGULAR;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** From its stored lowercase name; null for null. */
    public static RouteCapacity of(String value) {
        return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
