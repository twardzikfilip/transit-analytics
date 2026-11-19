package com.transitanalytics.buslanes;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverpassBusLanesTest {

    @Test
    void countsBusLanesEachWayFromTheTags() {
        // Per-lane lists: each designated lane, forward and backward.
        assertThat(OverpassBusLanes.lanes(Map.of("oneway", "yes", "bus:lanes", "|designated"))).containsExactly(1, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("bus:lanes:forward", "designated|designated|",
                "psv:lanes:backward", "|designated"))).containsExactly(2, 1);
        // Side tags: right is with the traffic; left is against it on a two-way street, with it on a one-way one.
        assertThat(OverpassBusLanes.lanes(Map.of("busway:right", "lane"))).containsExactly(1, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("busway:left", "lane"))).containsExactly(0, 1);
        assertThat(OverpassBusLanes.lanes(Map.of("busway:left", "lane", "oneway", "yes"))).containsExactly(1, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("busway:left", "opposite_lane"))).containsExactly(0, 1);
        assertThat(OverpassBusLanes.lanes(Map.of("busway:both", "lane"))).containsExactly(1, 1);
        // busway=lane: both sides of a two-way street, the one direction of a one-way one.
        assertThat(OverpassBusLanes.lanes(Map.of("busway", "lane"))).containsExactly(1, 1);
        assertThat(OverpassBusLanes.lanes(Map.of("busway", "lane", "oneway", "yes"))).containsExactly(1, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("busway", "opposite_lane"))).containsExactly(0, 1);
        // A road for buses only; a bare count; a one-way street drawn against the traffic.
        assertThat(OverpassBusLanes.lanes(Map.of("highway", "busway", "oneway", "yes"))).containsExactly(1, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("lanes:bus", "2"))).containsExactly(2, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("lanes:psv", "many"))).containsExactly(0, 0);
        assertThat(OverpassBusLanes.lanes(Map.of("oneway", "-1", "busway:right", "lane"))).containsExactly(0, 1);
        // No bus lane: a turn lane list without one.
        assertThat(OverpassBusLanes.lanes(Map.of("bus:lanes", "yes|yes", "busway", "no"))).containsExactly(0, 0);
    }

    @Test
    void readsTheWaysWithBusLanesFromAnAnswer() {
        var answer = JsonMapper.builder().build().readTree("""
                {"elements":[
                  {"type":"way","id":11,"tags":{"name":"Lipowa","highway":"secondary","oneway":"yes",
                   "bus:lanes":"|designated"},"geometry":[{"lat":53.13,"lon":23.15},{"lat":53.13,"lon":23.16}]},
                  {"type":"way","id":12,"tags":{"highway":"service","bus:lanes":"yes"},
                   "geometry":[{"lat":53.1,"lon":23.1},{"lat":53.1,"lon":23.2}]},
                  {"type":"way","id":13,"tags":{"busway":"lane"},"geometry":[{"lat":53.1,"lon":23.1}]},
                  {"type":"node","id":14}
                ]}""");

        List<BusLane> lanes = OverpassBusLanes.parse(answer);

        assertThat(lanes).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo(11);
            assertThat(l.name()).isEqualTo("Lipowa");
            assertThat(l.highway()).isEqualTo("secondary");
            assertThat(l.forward()).isEqualTo(1);
            assertThat(l.backward()).isZero();
            assertThat(l.meters()).isBetween(660.0, 680.0);
        });
        assertThat(OverpassBusLanes.query(2910925)).contains("rel(2910925)").contains("out geom tags");
    }
}
