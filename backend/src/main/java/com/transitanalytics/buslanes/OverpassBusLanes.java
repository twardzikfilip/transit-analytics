package com.transitanalytics.buslanes;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Overpass query for a city's bus lanes, and reading its answer. */
public final class OverpassBusLanes {

    private static final Set<String> LANE = Set.of("lane", "track");

    private OverpassBusLanes() {
    }

    /** Every way in the area of the relation (the city limits) tagged with a lane for buses, with its points. */
    public static String query(long areaRelation) {
        String a = "(area.city)";
        return """
                [out:json][timeout:150];
                rel(%d);map_to_area->.city;
                (
                  way%s["busway"~"^(lane|opposite_lane|track)$"];
                  way%s["busway:left"~"^(lane|opposite_lane|track)$"];
                  way%s["busway:right"~"^(lane|opposite_lane|track)$"];
                  way%s["busway:both"~"^(lane|track)$"];
                  way%s["bus:lanes"~"designated"];
                  way%s["bus:lanes:forward"~"designated"];
                  way%s["bus:lanes:backward"~"designated"];
                  way%s["psv:lanes"~"designated"];
                  way%s["psv:lanes:forward"~"designated"];
                  way%s["psv:lanes:backward"~"designated"];
                  way%s["lanes:bus"];
                  way%s["lanes:psv"];
                  way%s["highway"="busway"];
                );
                out geom tags;
                """.formatted(areaRelation, a, a, a, a, a, a, a, a, a, a, a, a, a);
    }

    /** The bus lanes in an Overpass answer ({@code out geom tags}); ways whose tags give no bus lane are left out. */
    public static List<BusLane> parse(JsonNode answer) {
        List<BusLane> lanes = new ArrayList<>();
        for (JsonNode e : answer.path("elements")) {
            if (!"way".equals(e.path("type").asString()) || !e.has("geometry")) {
                continue;
            }
            Map<String, String> tags = new HashMap<>();
            e.path("tags").properties().forEach(t -> tags.put(t.getKey(), t.getValue().asString()));
            int[] count = lanes(tags);
            if (count[0] + count[1] == 0) {
                continue;
            }
            List<double[]> points = new ArrayList<>();
            for (JsonNode p : e.path("geometry")) {
                points.add(new double[] {p.path("lat").asDouble(), p.path("lon").asDouble()});
            }
            if (points.size() >= 2) {
                lanes.add(new BusLane(e.path("id").asLong(), tags.get("name"), tags.get("highway"), count[0], count[1],
                        points));
            }
        }
        return lanes;
    }

    /**
     * Bus lanes on a way from its tags, {forward, backward} (in the way's direction and against it). Lanes listed
     * per lane ({@code bus:lanes=|designated|}) count each designated lane; side tags ({@code busway:right=lane})
     * one lane on that side (traffic keeps right; on a one-way street a left-side lane runs with the traffic).
     */
    static int[] lanes(Map<String, String> t) {
        String oneway = t.getOrDefault("oneway", "no");
        boolean oneWay = Set.of("yes", "1", "true", "-1").contains(oneway);
        int f = 0;
        int b = 0;
        if ("busway".equals(t.get("highway"))) {
            f = 1;
            b = oneWay ? 0 : 1;
        }
        for (String k : List.of("bus:lanes", "psv:lanes", "bus:lanes:forward", "psv:lanes:forward")) {
            f = Math.max(f, designated(t.get(k)));
        }
        for (String k : List.of("bus:lanes:backward", "psv:lanes:backward")) {
            b = Math.max(b, designated(t.get(k)));
        }
        String busway = t.get("busway");
        if (lane(busway)) {
            f = Math.max(f, 1);
            b = oneWay ? b : Math.max(b, 1);
        } else if ("opposite_lane".equals(busway)) {
            b = Math.max(b, 1);
        }
        if (lane(t.get("busway:right"))) {
            f = Math.max(f, 1);
        }
        String left = t.get("busway:left");
        if (lane(left)) {
            if (oneWay) {
                f = Math.max(f, 1);
            } else {
                b = Math.max(b, 1);
            }
        } else if ("opposite_lane".equals(left)) {
            b = Math.max(b, 1);
        }
        if (lane(t.get("busway:both"))) {
            f = Math.max(f, 1);
            b = Math.max(b, 1);
        }
        for (String k : List.of("lanes:bus", "lanes:psv")) {
            if (f + b == 0 && t.containsKey(k)) {
                try {
                    f = Math.max(0, Integer.parseInt(t.get(k).trim()));
                } catch (NumberFormatException ignored) {
                    // not a count
                }
            }
        }
        // oneway=-1: traffic runs against the way's direction, and so do its lanes.
        return "-1".equals(oneway) ? new int[] {b, f} : new int[] {f, b};
    }

    /** A lane or a track (a lane separated by a kerb); {@code Set.contains(null)} would throw. */
    private static boolean lane(String value) {
        return value != null && LANE.contains(value);
    }

    private static int designated(String lanes) {
        if (lanes == null) {
            return 0;
        }
        int n = 0;
        for (String lane : lanes.split("\\|", -1)) {
            if (lane.trim().equals("designated")) {
                n++;
            }
        }
        return n;
    }
}
