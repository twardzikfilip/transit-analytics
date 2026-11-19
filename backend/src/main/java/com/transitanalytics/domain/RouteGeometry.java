package com.transitanalytics.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a line runs relative to the city centre (rules set in {@code classification/routes.yml}).
 * <ul>
 *     <li>radial: links the centre with the periphery;</li>
 *     <li>diameter: links one side of the city with the opposite side through the centre;</li>
 *     <li>arcantial: two radial legs at an angle to each other (30°–143°), joined in or by the centre;</li>
 *     <li>tangential: links outer parts to each other in an arc around the centre, without serving it;</li>
 *     <li>feeder: like radial, but ends in the city short of the centre (mostly from the suburbs).</li>
 * </ul>
 */
public enum RouteGeometry {
    RADIAL, DIAMETER, ARCANTIAL, TANGENTIAL, FEEDER;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @jakarta.persistence.Converter(autoApply = true)
    public static class Converter extends LowercaseEnumConverter<RouteGeometry> {
        public Converter() {
            super(RouteGeometry.class);
        }
    }
}
