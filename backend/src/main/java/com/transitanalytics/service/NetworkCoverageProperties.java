package com.transitanalytics.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * How the Network coverage page works out coverage and densities ({@code map/network-coverage.yml}).
 *
 * @param radii            walking distances to the nearest stop, in metres; the first leads in the tables
 * @param beltKm           the belt: villages and towns reaching within this distance of the city limits
 * @param beltFarKm        the same in the arc from Supraśl round the south to Baciuty
 * @param cellMeters       grid squares in the city
 * @param beltCellMeters   grid squares over the belt
 * @param largeForestShare a place with at least this share of woods also gets its density without them
 * @param listedWoodsKm2   unnamed woods at least this large are listed on their own
 * @param drawnWoodsKm2    woods and open land in the belt smaller than this are counted but not drawn
 */
@ConfigurationProperties("app.network-coverage")
public record NetworkCoverageProperties(List<Integer> radii, double beltKm, double beltFarKm, double cellMeters,
                                        double beltCellMeters, double largeForestShare, double listedWoodsKm2,
                                        double drawnWoodsKm2) {

    public NetworkCoverageProperties {
        radii = radii == null || radii.isEmpty() ? List.of(400) : List.copyOf(radii);
        beltKm = beltKm <= 0 ? 5 : beltKm;
        beltFarKm = beltFarKm <= 0 ? 9 : beltFarKm;
        cellMeters = cellMeters <= 0 ? 10 : cellMeters;
        beltCellMeters = beltCellMeters <= 0 ? 20 : beltCellMeters;
        largeForestShare = largeForestShare <= 0 ? 0.10 : largeForestShare;
        listedWoodsKm2 = listedWoodsKm2 <= 0 ? 0.1 : listedWoodsKm2;
        drawnWoodsKm2 = drawnWoodsKm2 < 0 ? 0 : drawnWoodsKm2;
    }

    /** The defaults, as map/network-coverage.yml has them. */
    public static NetworkCoverageProperties defaults() {
        return new NetworkCoverageProperties(List.of(400, 500, 600), 5, 9, 10, 20, 0.10, 0.1, 0.002);
    }
}
