package com.transitanalytics.dto;

import java.util.List;

/**
 * How much of the city lies within walking distance of the nearest stop, at each distance, with and without its
 * woods; the woods; and the population density of the city's districts and of the villages and towns around it, also
 * without their woods. Areas in km²; points are {@code [latitude, longitude]}.
 *
 * @param stops      where the city's lines stop (one point per place)
 * @param cellMeters the grid the city's areas are measured on (squares of this side)
 * @param city       the city's border and area
 * @param coverages  one per walking distance, shortest first
 * @param forests    the woods in the city, the largest first; each named one, the larger unnamed ones, the rest together
 * @param forestKm2  all woods in the city together (overlapping mapped woods counted once)
 * @param districts  the city's districts
 * @param forestShare a place with at least this share of woods also gets its density without them
 * @param official   the city's population by Statistics Poland, registered or not (the districts' figures are the
 *                   city's register, which has fewer)
 * @param belt       the villages and towns around the city
 */
public record NetworkCoverageDto(List<double[]> stops, double cellMeters, Place city, List<RadiusCoverage> coverages,
                                 List<Forest> forests, double forestKm2, List<PlaceDensity> districts,
                                 double forestShare, Population official, Sources sources, Belt belt) {

    /** Outer rings and holes, each ring a list of points. */
    public record Shape(List<List<double[]>> outer, List<List<double[]>> inner) {
    }

    public record Place(String name, Shape shape, double areaKm2) {
    }

    /** The city within {@code radiusMeters} of the nearest stop: all of it, and the part outside its woods. */
    public record RadiusCoverage(int radiusMeters, Coverage city, Coverage withoutForest) {
    }

    /** @param share coveredKm2 / areaKm2 */
    public record Coverage(double areaKm2, double coveredKm2, double share) {
    }

    /**
     * @param name     the wood's name, or what it is (e.g. "Woodland in Dojlidy") when unnamed
     * @param named    whether OpenStreetMap names it
     * @param district the district most of it lies in
     * @param pieces   mapped areas it is made of
     * @param shapes   its outlines
     */
    public record Forest(String name, boolean named, String district, double areaKm2, int pieces,
                         List<Shape> shapes) {
    }

    /**
     * A district of the city, or a village or town around it (areas outside the city limits).
     *
     * @param kind             "district", "village" or "town"
     * @param gmina            the gmina a village or town is in; null for a district
     * @param population       residents, or null when no figure was found
     * @param populationDate   when the figure is from, e.g. 2021-03-31 (the 2021 census)
     * @param populationSource where it comes from
     * @param density          residents per km²; null without a figure
     * @param forestKm2        woods in it
     * @param landKm2          it without its woods
     * @param landDensity      residents per km² of {@code landKm2}; null without a figure
     * @param largeForest      woods cover at least {@link NetworkCoverageDto#forestShare} of it
     * @param coveredShares    the share of it within each walking distance of a stop, as {@code coverages}
     */
    public record PlaceDensity(String name, String kind, String gmina, Shape shape, double areaKm2, Integer population,
                               String populationDate, String populationSource, Double density, double forestKm2,
                               double landKm2, Double landDensity, boolean largeForest, List<Double> coveredShares) {
    }

    /** A population figure, when it is from and where it comes from. */
    public record Population(int population, String date, String source) {
    }

    /** Where the shapes and the districts' residents come from. */
    public record Sources(String shapes, String shapesFetched, String population) {
    }

    /**
     * The suburban belt: every village (sołectwo) and town reaching within {@code distanceKm} of the city limits.
     *
     * @param woods outlines of the woods in them (the larger pieces; all count in the areas)
     */
    public record Belt(double distanceKm, double cellMeters, List<PlaceDensity> places, List<Shape> woods) {
    }
}
