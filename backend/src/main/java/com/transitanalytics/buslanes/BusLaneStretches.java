package com.transitanalytics.buslanes;

import com.transitanalytics.geo.Geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bus lanes grouped into stretches for the report: one per street and direction of travel (north, east, south or
 * westbound, from each section's ends), so both sides of an avenue are told apart.
 */
public final class BusLaneStretches {

    private static final String[] HEADINGS = {"northbound", "eastbound", "southbound", "westbound"};

    /**
     * A stretch.
     *
     * @param id      its number (index into the list)
     * @param name    the street; for an unnamed section "unnamed road"
     * @param heading northbound, eastbound, southbound or westbound
     * @param meters  length of its sections
     * @param paths   its sections, [latitude, longitude] in the direction of travel
     */
    public record Stretch(int id, String name, String heading, double meters, List<Long> ways,
                          List<List<double[]>> paths) {
    }

    private final List<Stretch> stretches;
    /** Stretch per lane (index) and direction: [lane][0 forward, 1 backward]; -1 none. */
    private final int[][] of;

    public BusLaneStretches(List<BusLane> lanes) {
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        of = new int[lanes.size()][2];
        for (int i = 0; i < lanes.size(); i++) {
            BusLane lane = lanes.get(i);
            of[i][0] = -1;
            of[i][1] = -1;
            for (int d = 0; d < 2; d++) {
                if ((d == 0 ? lane.forward() : lane.backward()) == 0) {
                    continue;
                }
                List<double[]> path = new ArrayList<>(lane.points());
                if (d == 1) {
                    Collections.reverse(path);
                }
                String heading = HEADINGS[(int) Math.round(Geo.heading(path.get(0), path.get(path.size() - 1)) / 90) % 4];
                String name = lane.name() == null ? "unnamed road" : lane.name();
                // Unnamed sections (bus bays, slip roads) aren't one street: each is its own stretch.
                String key = lane.name() == null ? "#" + lane.id() + d : name + "|" + heading;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[] {i, d, path, heading, name});
            }
        }
        List<Stretch> list = new ArrayList<>();
        for (List<Object[]> group : groups.values()) {
            int id = list.size();
            double meters = 0;
            List<Long> ways = new ArrayList<>();
            List<List<double[]>> paths = new ArrayList<>();
            for (Object[] g : group) {
                int lane = (int) g[0];
                of[lane][(int) g[1]] = id;
                meters += lanes.get(lane).meters();
                ways.add(lanes.get(lane).id());
                @SuppressWarnings("unchecked")
                List<double[]> path = (List<double[]>) g[2];
                paths.add(path);
            }
            list.add(new Stretch(id, (String) group.get(0)[4], (String) group.get(0)[3], meters, ways, paths));
        }
        this.stretches = List.copyOf(list);
    }

    public List<Stretch> all() {
        return stretches;
    }

    /** The stretch of a match. */
    public int of(BusLaneMatcher.Match match) {
        return of[match.lane()][match.forward() ? 0 : 1];
    }
}
