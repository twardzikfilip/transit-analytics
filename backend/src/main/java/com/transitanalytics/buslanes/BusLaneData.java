package com.transitanalytics.buslanes;

import java.time.Instant;
import java.util.List;

/** The bus lanes as fetched: where from, when, and the lanes. */
public record BusLaneData(String source, Instant fetchedAt, List<BusLane> lanes) {

    public BusLaneData {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
    }

    /** Length of bus lane in km, each direction's lane counted (two lanes side by side: twice). */
    public double laneKm() {
        return lanes.stream().mapToDouble(l -> l.meters() * (l.forward() + l.backward())).sum() / 1000;
    }
}
