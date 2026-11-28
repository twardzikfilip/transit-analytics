package com.transitanalytics.rail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The network of the City Rail page, by station ids of the feed {@code rail}.
 *
 * @param hub  the station in the city that the routes start from
 * @param ends where they end: one route each
 */
@ConfigurationProperties("app.city-rail")
public record CityRailProperties(String hub, List<String> ends) {

    public CityRailProperties {
        ends = ends == null ? List.of() : List.copyOf(ends);
    }
}
