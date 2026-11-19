package com.transitanalytics.zbiorkom;

import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.service.LineSubtypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ZbiorkomBialystokServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String LATEST = "http://recorder.test/live/bialystok/positions";

    private MockRestServiceServer recorder;
    private BialystokFeed feed;
    private ZbiorkomBialystokService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://recorder.test");
        recorder = MockRestServiceServer.bindTo(builder).build();
        feed = mock(BialystokFeed.class);
        LineSubtypes subtypes = mock(LineSubtypes.class);
        given(subtypes.of("18")).willReturn(RouteSubtype.URBAN);
        service = new ZbiorkomBialystokService(new RecorderClient(builder.build(), JsonMapper.builder().build()),
                feed, subtypes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String snapshot(String fetchedAt) {
        return """
                {"fetchedAt":"%s","positions":[{"vehicleId":7,"vehicleNumber":"230","type":"BUS","agency":"BKM",
                 "model":"Solaris Urbino 12","routeName":"18","brigade":"018-02","headsign":"Warzywna",
                 "latitude":53.15,"longitude":23.07,"bearing":90.0,"delaySeconds":30,
                 "timestamp":"2026-11-19T11:59:55Z","upcomingStops":[{"sequence":3,"name":"Next",
                 "scheduledArrival":"2026-11-19T12:01:00Z"}]}]}""".formatted(fetchedAt);
    }

    private static FeedPosition direct() {
        return new FeedPosition(8L, "1051", "BUS", "BKM", null, "18", null, null, null, 53.1, 23.1, null, null,
                NOW, List.of());
    }

    @Test
    void takesTheRecordersLatestSnapshotAndAddsTheKindOfLine() {
        recorder.expect(requestTo(LATEST))
                .andRespond(withSuccess(snapshot("2026-11-19T11:59:58Z"), MediaType.APPLICATION_JSON));

        List<LivePositionDto> positions = service.positions();

        assertThat(positions).singleElement().satisfies(p -> {
            assertThat(p.vehicleId()).isEqualTo(7L);
            assertThat(p.subtype()).isEqualTo(RouteSubtype.URBAN);
            assertThat(p.delaySeconds()).isEqualTo(30L);
            assertThat(p.upcomingStops()).singleElement().satisfies(s -> {
                assertThat(s.name()).isEqualTo("Next");
                assertThat(s.scheduledArrival()).isEqualTo(Instant.parse("2026-11-19T12:01:00Z"));
            });
        });
        verify(feed, never()).poll();
        recorder.verify();
    }

    @Test
    void asksZbiorkomItselfWhenTheRecorderIsDownOrBehind() {
        recorder.expect(requestTo(LATEST)).andRespond(withServerError());
        recorder.expect(requestTo(LATEST))
                .andRespond(withSuccess(snapshot("2026-11-19T11:58:00Z"), MediaType.APPLICATION_JSON));
        given(feed.poll()).willReturn(new BialystokFeed.Snapshot(new byte[0], NOW, List.of(direct())));

        assertThat(service.positions()).extracting(LivePositionDto::vehicleId).containsExactly(8L);
        assertThat(service.positions()).extracting(LivePositionDto::vehicleId).as("2 minutes old").containsExactly(8L);
        verify(feed, times(2)).poll();
        recorder.verify();
    }
}
