package com.transitanalytics.service;

import java.util.BitSet;
import java.util.List;

/**
 * Places (districts, villages) laid on an {@link AreaGrid}: each cell of {@code within} belongs to the first place
 * whose border takes it (so places that overlap a little count no cell twice), and can be counted per place.
 * Place numbers start at 1; 0 is no place.
 */
final class PlaceGrid {

    final AreaGrid grid;
    final int places;
    private final short[] placeOf;

    /** @param placeRings each place's rings in metres (see {@link AreaGrid#fill}) */
    PlaceGrid(AreaGrid grid, BitSet within, List<List<double[][]>> placeRings) {
        this.grid = grid;
        this.places = placeRings.size();
        this.placeOf = new short[grid.size()];
        for (int i = 0; i < placeRings.size(); i++) {
            short id = (short) (i + 1);
            grid.fill(placeRings.get(i), cell -> {
                if (placeOf[cell] == 0 && within.get(cell)) {
                    placeOf[cell] = id;
                }
            });
        }
    }

    /** The place a cell belongs to, 0 for none. */
    int placeOf(int cell) {
        return placeOf[cell];
    }

    /** Per place (index = place number), its cells that are also in {@code set}; all its cells when null. */
    long[] count(BitSet set) {
        long[] counts = new long[places + 1];
        for (int cell = 0; cell < placeOf.length; cell++) {
            if (placeOf[cell] != 0 && (set == null || set.get(cell))) {
                counts[placeOf[cell]]++;
            }
        }
        return counts;
    }
}
