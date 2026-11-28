package com.transitanalytics.routes;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DepotRouterTest {

    private static final double[] DEPOT = {53.1167, 23.1297};
    private static final double[] STOP = {53.109, 23.115};

    private static MapProperties routing(boolean enabled) {
        return new MapProperties(new MapProperties.Routing(enabled, "https://osrm.example", Duration.ofSeconds(1)),
                List.of());
    }

    @Test
    void followsTheStreetsAndRemembersTheWay() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://osrm.example/route/v1/driving/23.129700,53.116700;23.115000,53.109000"
                        + "?overview=full&geometries=geojson"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"Ok","routes":[{"distance":1640,"geometry":{"type":"LineString",
                         "coordinates":[[23.1297,53.1167],[23.12,53.112],[23.115,53.109]]}}]}""",
                        MediaType.APPLICATION_JSON));
        DepotRouter router = new DepotRouter(routing(true), builder, JsonMapper.builder().build());

        DepotRouter.Way way = router.way(DEPOT, STOP);
        assertThat(way.routed()).isTrue();
        assertThat(way.points()).hasSize(3);
        assertThat(way.points().get(1)).containsExactly(53.112, 23.12);
        // Asked once: the second time it is remembered.
        assertThat(router.way(DEPOT, STOP)).isSameAs(way);
        server.verify();
    }

    @Test
    void drawsAStraightRouteWhenTheRouterFailsOrIsOff() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://osrm.example/route/")))
                .andRespond(withServerError());
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://osrm.example/route/")))
                .andRespond(withSuccess("{\"code\":\"NoRoute\",\"routes\":[]}", MediaType.APPLICATION_JSON));
        DepotRouter router = new DepotRouter(routing(true), builder, JsonMapper.builder().build());
        assertThat(router.way(DEPOT, STOP).routed()).isFalse();
        DepotRouter.Way none = router.way(DEPOT, STOP);
        assertThat(none.routed()).isFalse();
        assertThat(none.points()).containsExactly(DEPOT, STOP);

        DepotRouter off = new DepotRouter(routing(false), RestClient.builder(), JsonMapper.builder().build());
        assertThat(off.way(DEPOT, STOP).points()).containsExactly(DEPOT, STOP);
    }
}
