package com.transitanalytics.dto;

import java.util.List;

/**
 * How much of the city lies within walking distance of a stop, with and without its woods, and the districts'
 * population densities, with and without the woods in them. Areas in km²; points are {@code [latitude, longitude]}.
 *
 * @param radiusMeters   the walking distance around each stop
 * @param stops          where the city's stops are (one point per place; stops served by the city's lines)
 * @param cellMeters     the grid the areas are measured on (square cells of this side)
 * @param city           the city's border and area
 * @param coverage       the whole city
 * @param withoutForest  the city without its woods
 * @param forests        the woods, the largest first; each named one, the larger unnamed ones, and the rest together
 * @param forestKm2      all woods in the city together (overlapping mapped woods counted once)
 * @param forestShare    share of a district's area in woods from which its density is adjusted (e.g. 0.1)
 * @param belt           the villages and towns around the city
 * @param officialPopulation the city's population by Statistics Poland (GUS), registered or not; the districts'
 *                       figures are from the city's register, which has fewer
 */
public record NetworkCoverageDto(int radiusMeters, List<double[]> stops, double cellMeters, Place city,
                                 Coverage coverage, Coverage withoutForest, List<Forest> forests, double forestKm2,
                                 List<District> districts, double forestShare, int officialPopulation,
                                 Sources sources, Belt belt) {

    /** Outer rings and holes, each ring a list of points. */
    public record Shape(List<List<double[]>> outer, List<List<double[]>> inner) {
    }

    public record Place(String name, Shape shape, double areaKm2) {
    }

    /** @param share coveredKm2 / areaKm2 */
    public record Coverage(double areaKm2, double coveredKm2, double share) {
    }

    /**
     * @param name     the wood's name, or what it is (e.g. "Woodland in Dojlidy") when unnamed
     * @param named    whether OpenStreetMap names it
     * @param district the district most of it lies in
     * @param pieces   mapped areas it is made of
     * @param shapes   its outlines (empty for the small woods taken together, drawn with the others)
     */
    public record Forest(String name, boolean named, String district, double areaKm2, int pieces,
                         List<Shape> shapes) {
    }

    /**
     * @param population     residents registered, for permanent or temporary stay
     * @param density        residents per km²
     * @param forestKm2      woods in the district
     * @param landKm2        the district without its woods
     * @param landDensity    residents per km² of {@code landKm2}
     * @param largeForest    woods cover at least {@link NetworkCoverageDto#forestShare} of the district
     * @param coveredShare   share of the district within walking distance of a stop
     */
    public record District(String name, Shape shape, double areaKm2, int population, double density,
                           double forestKm2, double landKm2, double landDensity, boolean largeForest,
                           double coveredShare) {
    }

    /** Where the data comes from. */
    public record Sources(String shapes, String shapesFetched, String population) {
    }

    /**
     * The suburban belt: every village (sołectwo) and town reaching within {@code distanceKm} of the city limits.
     *
     * @param woods outlines of the woods in them (the larger pieces; all count in the areas)
     */
    public record Belt(double distanceKm, double cellMeters, List<BeltPlace> places, List<Shape> woods) {
    }

    /**
     * A village or town of the belt; areas outside the city limits.
     *
     * @param population      residents, or null when no figure was found
     * @param populationDate  when the figure is from, e.g. 2021-03-31 (the 2021 census)
     * @param populationSource where it comes from
     */
    public record BeltPlace(String name, String gmina, String kind, Shape shape, double areaKm2, Integer population,
                         String populationDate, String populationSource, Double density, double forestKm2,
                         double landKm2, Double landDensity, boolean largeForest, double coveredShare) {
    }
}
