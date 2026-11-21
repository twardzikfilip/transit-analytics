package com.transitanalytics.service;

import com.transitanalytics.dto.NetworkCoverageDto.Population;
import com.transitanalytics.dto.NetworkCoverageDto.Shape;
import com.transitanalytics.geo.CsvTable;
import com.transitanalytics.geo.Geo;
import com.transitanalytics.geo.PlaceData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference data the Network coverage page works from, as read from {@code places/} (see its README): the city's
 * border, districts, woods and the valley of the river Biała; the villages and towns around it and their woods; and the residents of each.
 */
record PlaceReferenceData(Areas city, BeltAreas belt, Map<String, Residents> districtPopulation,
                          String districtPopulationSource, Map<String, Residents> beltPopulation, Population official) {

    static final String CITY = "osm/bialystok.json";
    static final String BELT = "osm/belt.json";
    static final String DISTRICT_POPULATION = "population/districts.csv";
    static final String BELT_POPULATION = "population/belt.csv";
    static final String CITY_POPULATION = "population/city.csv";

    /** A shape: points are [latitude, longitude]. */
    record Feature(String osm, String name, List<List<double[]>> outer, List<List<double[]>> inner) {
        Shape shape() {
            return new Shape(outer, inner);
        }

        /** All rings in metres, for {@link AreaGrid#fill}. */
        List<double[][]> rings() {
            List<double[][]> rings = new ArrayList<>();
            for (List<List<double[]>> group : List.of(outer, inner)) {
                for (List<double[]> ring : group) {
                    rings.add(ring.stream().map(p -> Geo.xy(p[0], p[1])).toArray(double[][]::new));
                }
            }
            return rings;
        }
    }

    /** @param valley the valley of the river Biała where nobody lives, one feature per stretch; may be absent */
    record Areas(String source, String fetched, Feature city, List<Feature> districts, List<Feature> forests,
                 List<Feature> valley) {
        Areas {
            valley = valley == null ? List.of() : valley;
        }
    }

    /** A village or town of the belt. */
    record BeltFeature(String osm, String name, String gmina, String kind, List<List<double[]>> outer,
                       List<List<double[]>> inner) {
        Feature feature() {
            return new Feature(osm, name, outer, inner);
        }
    }

    record BeltAreas(String source, List<BeltFeature> villages, List<Feature> woods) {
    }

    /** A village's residents: null when no figure was found. */
    record Residents(Integer population, String date, String source) {
        static final Residents UNKNOWN = new Residents(null, null, null);
    }

    /** Reads it all (overrides in ./config/places/ win, see {@link PlaceData}). */
    static PlaceReferenceData load() {
        CsvTable districts = PlaceData.csv(DISTRICT_POPULATION);
        Map<String, Residents> districtPopulation = new HashMap<>();
        for (CsvTable.Row row : districts.rows()) {
            // Everyone on the register: permanent and temporary stay.
            districtPopulation.put(row.get("name"), new Residents(row.integer("permanent") + row.integer("temporary"),
                    row.text("date"), row.text("source")));
        }
        Map<String, Residents> beltPopulation = new HashMap<>();
        for (CsvTable.Row row : PlaceData.csv(BELT_POPULATION).rows()) {
            beltPopulation.put(row.get("osm"), new Residents(row.integer("population"), row.text("date"),
                    row.text("source")));
        }
        CsvTable.Row city = PlaceData.csv(CITY_POPULATION).rows().get(0);
        return new PlaceReferenceData(PlaceData.json(CITY, Areas.class), PlaceData.json(BELT, BeltAreas.class),
                Map.copyOf(districtPopulation), districts.about(), Map.copyOf(beltPopulation),
                new Population(city.integer("population"), city.text("date"), city.text("source")));
    }
}
