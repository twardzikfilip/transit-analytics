package com.transitanalytics.dto;

/**
 * Białystok's city limits, its city centre (Osiedle Centrum) and the smaller centre routes are classified by (set in
 * classification/routes.yml), as closed rings of {@code [latitude, longitude]} pairs.
 * Data © OpenStreetMap contributors (ODbL).
 */
public record BoundaryDto(double[][] city, double[][] centre, double[][] routeCentre) {
}
