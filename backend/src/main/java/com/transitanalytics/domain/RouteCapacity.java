package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How much a line carries, from its buses and frequency (rules set in {@code classification/routes.yml}): main,
 * primary, secondary, tertiary or irregular, from the strongest to the weakest.
 */
public enum RouteCapacity {
    MAIN, PRIMARY, SECONDARY, TERTIARY, IRREGULAR;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @jakarta.persistence.Converter(autoApply = true)
    public static class Converter extends LowercaseEnumConverter<RouteCapacity> {
        public Converter() {
            super(RouteCapacity.class);
        }
    }
}
