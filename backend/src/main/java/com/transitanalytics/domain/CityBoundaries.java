package com.transitanalytics.domain;

import com.transitanalytics.geo.Geo;
import com.transitanalytics.geo.PlaceData;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;

/**
 * Boundaries of Białystok as closed polygon rings of {latitude, longitude} pairs (WGS 84, first point repeated at the
 * end): the city limits and the city centre (Osiedle Centrum). Read from {@code places/osm/bialystok.json}, the same
 * OpenStreetMap shapes the Network coverage page uses (see places/README.md; data © OpenStreetMap contributors, ODbL).
 */
public final class CityBoundaries {

    /** The district that is the city centre. */
    static final String CENTRE_DISTRICT = "Centrum";

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Shape(String name, List<List<double[]>> outer) {
        /** The largest outer ring. */
        double[][] ring() {
            return outer.stream().max(Comparator.comparingInt(List::size)).orElseThrow().toArray(double[][]::new);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Shapes(Shape city, List<Shape> districts) {
    }

    private static final Shapes SHAPES = PlaceData.json("osm/bialystok.json", Shapes.class);

    /** City limits: OpenStreetMap relation 2910925, gmina miejska Białystok. */
    public static final double[][] CITY = SHAPES.city().ring();

    /** City centre: OpenStreetMap relation 4091287, Osiedle Centrum. */
    public static final double[][] CENTRE = SHAPES.districts().stream()
            .filter(d -> CENTRE_DISTRICT.equals(d.name())).findFirst().orElseThrow().ring();

    private CityBoundaries() {
    }

    /** Whether a place is inside the city limits ({@link #CITY}). */
    public static boolean inCity(double lat, double lon) {
        return Geo.inside(new double[] {lat, lon}, CITY);
    }
}
