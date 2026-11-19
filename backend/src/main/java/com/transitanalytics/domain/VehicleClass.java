package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Size class of a bus, from its length (bands set in {@code classification/vehicles.yml}): mini, midi, maxi (a
 * standard 12 m bus), mega (articulated, 15 m and more) or a double-decker. Stored and sent in lowercase.
 */
public enum VehicleClass {
    MINI, MIDI, MAXI, MEGA, DOUBLE_DECKER;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @jakarta.persistence.Converter(autoApply = true)
    public static class Converter extends LowercaseEnumConverter<VehicleClass> {
        public Converter() {
            super(VehicleClass.class);
        }
    }
}
