package com.transitanalytics.security;

import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.dto.NetworkCoverageDto;
import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.dto.HotspotDto;
import com.transitanalytics.dto.LineStatsDto;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.dto.FleetDto;
import com.transitanalytics.gtfs.GtfsFeedService;
import com.transitanalytics.gtfs.GtfsFeedException;
import com.transitanalytics.gtfs.GtfsImportInProgressException;
import com.transitanalytics.gtfs.GtfsImportResult;
import com.transitanalytics.recording.PositionHistoryService;
import com.transitanalytics.service.BrigadeDayService;
import com.transitanalytics.service.NetworkCoverageService;
import com.transitanalytics.service.BrigadeScheduleService;
import com.transitanalytics.domain.CityBoundaries;
import com.transitanalytics.web.BoundaryController;
import com.transitanalytics.service.DelayGenerationService;
import com.transitanalytics.service.ExternalServiceException;
import com.transitanalytics.service.HotspotService;
import com.transitanalytics.service.LineStatsService;
import com.transitanalytics.service.FleetService;
import com.transitanalytics.service.ReportWindow;
import com.transitanalytics.service.ReportWindows;
import com.transitanalytics.service.TripAnomalyService;
import com.transitanalytics.service.VehicleService;

import com.transitanalytics.web.BrigadeController;
import com.transitanalytics.web.NetworkCoverageController;
import com.transitanalytics.web.DelayGenerationController;
import com.transitanalytics.web.GtfsController;
import com.transitanalytics.web.HotspotController;
import com.transitanalytics.web.LineStatsController;
import com.transitanalytics.web.PositionHistoryController;
import com.transitanalytics.web.TripAnomalyController;
import com.transitanalytics.web.VehicleController;
import com.transitanalytics.web.FleetController;
import com.transitanalytics.web.ZbiorkomController;
import com.transitanalytics.zbiorkom.ZbiorkomBialystokService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, VehicleController.class, ZbiorkomController.class,
        GtfsController.class, PositionHistoryController.class,
        LineStatsController.class, FleetController.class, BrigadeController.class, HotspotController.class,
        DelayGenerationController.class, TripAnomalyController.class, BoundaryController.class,
        NetworkCoverageController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, SecurityWebTest.Metrics.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.expiration-minutes=5",
        "app.cors.allowed-origins=http://localhost:4200"
})
class SecurityWebTest {

    private static final String VEHICLE_JSON = """
            {"vehicleNumber":"BUS-1","type":"BUS","capacity":50}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    @Autowired
    MeterRegistry meterRegistry;

    /** "Now" for windows left open, and the first recording ("all time"). */
    static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    static final Instant FIRST_RECORDING = Instant.parse("2026-11-01T09:30:00Z");

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /** The line-classification settings (the boundaries endpoint serves their centre). */
        @Bean
        com.transitanalytics.classification.RouteClassProperties routeClassProperties() {
            return com.transitanalytics.classification.ClassificationConfigs.routes();
        }

        @Bean
        ReportWindows reportWindows() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.from(FIRST_RECORDING));
            return new ReportWindows(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @MockitoBean
    AuthenticationManager authenticationManager;

    @MockitoBean
    VehicleService vehicleService;

    @MockitoBean
    ZbiorkomBialystokService zbiorkomBialystokService;

    @MockitoBean
    GtfsFeedService gtfsFeedService;

    @MockitoBean
    PositionHistoryService positionHistoryService;

    @MockitoBean
    LineStatsService lineStatsService;

    @MockitoBean
    FleetService fleetService;

    @MockitoBean
    BrigadeScheduleService brigadeScheduleService;

    @MockitoBean
    BrigadeDayService brigadeDayService;

    @MockitoBean
    HotspotService hotspotService;

    @MockitoBean
    DelayGenerationService delayGenerationService;

    @MockitoBean
    TripAnomalyService tripAnomalyService;

    @MockitoBean
    NetworkCoverageService networkCoverageService;

    @Test
    void loginReturnsToken() throws Exception {
        given(authenticationManager.authenticate(any())).willReturn(
                new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(loginCount("success")).isEqualTo(1.0);
    }

    @Test
    void badCredentialsReturn401() throws Exception {
        given(authenticationManager.authenticate(any())).willThrow(new BadCredentialsException("bad"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(loginCount("failure")).isEqualTo(1.0);
    }

    @Test
    void meRequiresToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("guest"))
                .andExpect(jsonPath("$.role").value("GUEST"));
    }

    @Test
    void guestCannotCreateVehicle() throws Exception {
        mvc.perform(post("/api/vehicles").header("Authorization", bearer("guest", "GUEST"))
                        .contentType(MediaType.APPLICATION_JSON).content(VEHICLE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateVehicle() throws Exception {
        given(vehicleService.create(any())).willReturn(new VehicleDto.Response(1L, "BUS-1", "BUS", 50, null, null, null));

        mvc.perform(post("/api/vehicles").header("Authorization", bearer("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VEHICLE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void guestCanReadLivePositions() throws Exception {
        given(zbiorkomBialystokService.positions()).willReturn(List.of(new LivePositionDto(7L, "230", "BUS", "BKM",
                "Solaris Urbino 12 W33", "18", "#006963", "018-02", "Warzywna", 53.15, 23.08, 152.0, 0L,
                Instant.parse("2026-11-16T09:00:00Z"), List.of(), null)));

        mvc.perform(get("/api/zbiorkom/bialystok/positions").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleNumber").value("230"))
                .andExpect(jsonPath("$[0].agency").value("BKM"))
                .andExpect(jsonPath("$[0].timestamp").value("2026-11-16T09:00:00Z"));
    }

    @Test
    void livePositionsRequireToken() throws Exception {
        mvc.perform(get("/api/zbiorkom/bialystok/positions")).andExpect(status().isUnauthorized());
    }

    @Test
    void upstreamFailureReturns502() throws Exception {
        given(zbiorkomBialystokService.positions())
                .willThrow(new ExternalServiceException("zbiorkom.live", new IOException("timeout")));

        mvc.perform(get("/api/zbiorkom/bialystok/positions").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Live data source unavailable"));
    }

    @Test
    void guestCannotImportGtfs() throws Exception {
        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanImportGtfs() throws Exception {
        given(gtfsFeedService.importFeed("bialystok")).willReturn(new GtfsImportResult("bialystok", "https://x", 10,
                Instant.parse("2026-11-16T10:00:00Z"), 1234, java.util.Map.of("stop", 1083L), java.util.Map.of(),
                List.of()));

        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.stop").value(1083));
    }

    @Test
    void gtfsImportErrorsMapToStatusCodes() throws Exception {
        given(gtfsFeedService.importFeed("bialystok"))
                .willThrow(new GtfsImportInProgressException("bialystok"))
                .willThrow(new GtfsFeedException("The feed has no stops.txt"));

        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/gtfs/bialystok/import").header("Authorization", bearer("admin", "ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The feed has no stops.txt"));
    }

    @Test
    void guestCanReadPositionHistory() throws Exception {
        Instant from = Instant.parse("2026-11-16T10:00:00Z");
        Instant to = Instant.parse("2026-11-16T11:00:00Z");
        given(positionHistoryService.fleet(from, to, "103", "BKM")).willReturn(new PositionHistoryDto.FleetSeries(from, to, 60,
                "103", List.of(new PositionHistoryDto.FleetPoint(from, 7.0, 7, 7, 30.0)),
                new PositionHistoryDto.Summary(7, 8, 7, 7, from, 30.0, from, from)));
        given(positionHistoryService.delays(from, to, null, null)).willReturn(List.of(
                new PositionHistoryDto.DelayBand(null, 2, 5, 40), new PositionHistoryDto.DelayBand(10, null, 1, 2)));

        mvc.perform(get("/api/positions/fleet").param("from", from.toString()).param("to", to.toString())
                        .param("route", "103").param("agency", "BKM").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].vehicles").value(7))
                .andExpect(jsonPath("$.stepSeconds").value(60))
                .andExpect(jsonPath("$.summary.peakVehicles").value(7));
        mvc.perform(get("/api/positions/delays").param("from", from.toString()).param("to", to.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toMinutes").value(2))
                .andExpect(jsonPath("$[1].fromMinutes").value(10));
        mvc.perform(get("/api/positions/fleet").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void positionHistoryRejectsBadWindows() throws Exception {
        given(positionHistoryService.fleet(any(), any(), any(), any()))
                .willThrow(new IllegalArgumentException("from must be before to"));

        mvc.perform(get("/api/positions/fleet").param("from", "2026-11-16T11:00:00Z").param("to", "2026-11-16T10:00:00Z")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must be before to"));
        mvc.perform(get("/api/positions/fleet").param("from", "yesterday").param("to", "2026-11-16T10:00:00Z")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/positions/fleet").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void guestCanReadLineStats() throws Exception {
        given(lineStatsService.weekdayReport()).willReturn(new LineStatsDto.Report("P_1349", 59, null, null, List.of(),
                List.of(new LineStatsDto.Period("17:00", "20:00")), 28,
                List.of(new LineStatsDto.Line(17L, "103", null, null, 19.8, 2400, 3000, 24.5, 26.0, 31.7, 40.2, 0.39, 0.2, 5, 4,
                        64, 95.0, 40.0))));

        mvc.perform(get("/api/lines/stats").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lengthKm").value(19.8))
                .andExpect(jsonPath("$.offPeak[0].to").value("20:00"));
        mvc.perform(get("/api/lines/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadTheFleetForAWindowAndOperators() throws Exception {
        var vehicle = new FleetDto.Vehicle(7L, "230", "Solaris Urbino 12", "BKM",
                com.transitanalytics.domain.VehicleClass.MAXI);
        var usage = new FleetDto.Usage(vehicle, 12.5, 1, List.of("103"));
        var from = Instant.parse("2026-11-15T20:00:00Z");
        var to = Instant.parse("2026-11-16T20:00:00Z");
        var report = new FleetDto.Report(from, to, 1, 2, List.of(new FleetDto.Agency("BKM", 2)),
                List.of(new FleetDto.ModelCount("Solaris Urbino 12", 1, 2)),
                List.of(new FleetDto.ModelCount("maxi", 1, 2)), List.of(new FleetDto.ModelCount("Solaris", 1, 2)),
                List.of(usage), List.of(usage),
                List.of(usage), List.of(new FleetDto.Breakdown(vehicle, Instant.parse("2026-11-16T07:00:00Z"), "103",
                "103-05", "Choroszcz", "Rynek", null, 1.2)));
        given(fleetService.report(new ReportWindow(from, to), Set.of())).willReturn(report);
        given(fleetService.report(new ReportWindow(FIRST_RECORDING, NOW), Set.of("BKM", "KPKM"))).willReturn(report);

        mvc.perform(get("/api/fleet").param("from", from.toString()).param("to", to.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencies[0].name").value("BKM"))
                .andExpect(jsonPath("$.models[0].fleet").value(2))
                .andExpect(jsonPath("$.breakdowns[0].daysOut").value(1.2))
                .andExpect(jsonPath("$.mostUsed[0].hours").value(12.5));
        // No window: all time, from the first recording until now.
        mvc.perform(get("/api/fleet").param("agency", "BKM").param("agency", "KPKM")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value(1));
        mvc.perform(get("/api/fleet").param("from", to.toString()).param("to", from.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fleet")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadOneBusOfTheFleet() throws Exception {
        var from = Instant.parse("2026-11-16T03:00:00Z");
        var detail = new FleetDto.VehicleDetail(new FleetDto.Vehicle(7L, "230", null, "BKM", null), from, NOW, 1.5,
                List.of("103"), NOW, List.of(new FleetDto.Day(LocalDate.of(2026, 11, 16), from, 1.5,
                List.of(new FleetDto.Run(18000, 21000, "103", "103-05", "Choroszcz")), List.of())),
                List.of(new FleetDto.Incident("missed", LocalDate.of(2026, 11, 16), "103", "103-05", "Choroszcz",
                        from, List.of(TripAnomalyDto.Phrase.of("betweenRuns")), List.of(), List.of(), List.of())));
        given(fleetService.vehicle(7L, new ReportWindow(from, NOW))).willReturn(detail);

        mvc.perform(get("/api/fleet/vehicles/7").param("from", from.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].runs[0].brigade").value("103-05"))
                .andExpect(jsonPath("$.incidents[0].kind").value("missed"));
        mvc.perform(get("/api/fleet/vehicles/7")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadStopHotspotsForAWindowAndLine() throws Exception {
        var hotspot = new HotspotDto.Hotspot(1, 53.136, 23.151, 19, 2.43, 20.8, 216, 82, 40, 39, 60,
                "Częstochowska/Piłsudskiego", 70, List.of(new HotspotDto.LineTime("21", 0.5)),
                List.of(new HotspotDto.Approach("E", 1.0)), 7);
        var from = Instant.parse("2026-11-09T20:00:00Z");
        var report = new HotspotDto.Report(from, NOW, "21", List.of("21"), 7, 216, 2.9, 2.4, List.of(hotspot));
        given(hotspotService.report(new ReportWindow(from, NOW), "21")).willReturn(report);

        mvc.perform(get("/api/hotspots").param("from", from.toString()).param("line", "21")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotspots[0].nearestStop").value("Częstochowska/Piłsudskiego"))
                .andExpect(jsonPath("$.hotspots[0].approaches[0].heading").value("E"));
        mvc.perform(get("/api/hotspots").param("from", "last week").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/hotspots")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadDelayGenerationForTheNetworkAndALine() throws Exception {
        var report = new DelayGenerationDto.Report(FIRST_RECORDING, NOW, 4, 8479, List.of(new DelayGenerationDto.Band("all", "All day")),
                List.of(new DelayGenerationDto.Stretch("Lipowa/Nowy Świat", "pl. Niepodległości", 53.13, 23.15,
                        53.131, 23.152, List.of("2"), List.of(707), List.of(35.6))),
                List.of());
        given(delayGenerationService.report(new ReportWindow(FIRST_RECORDING, NOW))).willReturn(report);
        var from = Instant.parse("2026-10-17T20:00:00Z");
        var to = Instant.parse("2026-11-16T20:00:00Z");
        var line = new DelayGenerationDto.LineReport(from, to, "21", 8, List.of(), List.of(
                new DelayGenerationDto.BrigadeGain("021-07", 84, 124.0, 664)), List.of());
        given(delayGenerationService.line(new ReportWindow(from, to), "21")).willReturn(line);

        mvc.perform(get("/api/delay-generation").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stretches[0].meanSeconds[0]").value(35.6));
        mvc.perform(get("/api/delay-generation/lines/21").param("from", from.toString()).param("to", to.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brigades[0].brigade").value("021-07"));
        mvc.perform(get("/api/delay-generation").param("from", NOW.plusSeconds(60).toString())
                .header("Authorization", bearer("guest", "GUEST"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/delay-generation/lines/21")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadAbnormalTripsForAWindow() throws Exception {
        var trip = new TripAnomalyDto.Trip("lost",
                List.of(TripAnomalyDto.Phrase.of("lostForGood", "bus", "319")), "012-02_P_1", "12", "012-02",
                "Hetmańska", "Starosielce", "Hetmańska", Instant.parse("2026-11-15T08:15:00Z"),
                Instant.parse("2026-11-15T09:00:00Z"), 25, List.of("319"), 1, 8, "Starosielce", "Gen. Fieldorfa",
                Instant.parse("2026-11-15T08:16:00Z"), Instant.parse("2026-11-15T09:24:00Z"), 0, 120, 60, null,
                null, null, null, List.of(), List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of());
        var from = Instant.parse("2026-11-17T11:45:00Z");
        var report = new TripAnomalyDto.Report(from, NOW,
                new TripAnomalyDto.Summary(40, 30, 29, 0, 0, 0, 0, 1, 0, 0, 0, 10, 0, 0), List.of(), 1, List.of(trip));
        given(tripAnomalyService.report(new ReportWindow(from, NOW))).willReturn(report);

        mvc.perform(get("/api/trip-anomalies").param("from", from.toString()).param("to", NOW.toString())
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.lost").value(1))
                .andExpect(jsonPath("$.trips[0].vehicles[0]").value("319"));
        mvc.perform(get("/api/trip-anomalies").param("from", NOW.toString()).param("to", from.toString())
                .header("Authorization", bearer("guest", "GUEST"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/trip-anomalies")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadABrigadesSchedule() throws Exception {
        given(brigadeScheduleService.schedule(eq("103-05"), any())).willReturn(new BrigadeDto.Schedule("103-05",
                LocalDate.of(2026, 11, 16), List.of(new BrigadeDto.Run("103", "Choroszcz", "Piastowska", "Choroszcz",
                Instant.parse("2026-11-16T05:00:00Z"), Instant.parse("2026-11-16T05:45:00Z")))));

        mvc.perform(get("/api/brigades/103-05/schedule").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceDay").value("2026-11-16"))
                .andExpect(jsonPath("$.runs[0].to").value("Choroszcz"));
        mvc.perform(get("/api/brigades/103-05/schedule")).andExpect(status().isUnauthorized());
        then(brigadeScheduleService).should().schedule("103-05", NOW);

        mvc.perform(get("/api/brigades/103-05/schedule").param("at", "2026-11-16T06:00:00Z")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk());
        then(brigadeScheduleService).should().schedule("103-05", Instant.parse("2026-11-16T06:00:00Z"));
    }

    @Test
    void guestCanReadTheBrigadesOfADayAndOnesDay() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 18);
        given(brigadeDayService.today()).willReturn(day);
        given(brigadeDayService.brigades(null)).willReturn(new BrigadeDto.BrigadeList("BKM",
                List.of(new BrigadeDto.Agency("BKM", "BKM")), List.of(new BrigadeDto.BrigadeRef("103-05",
                List.of("103")))));
        given(brigadeDayService.day("103-05", day)).willReturn(new BrigadeDto.Day("103-05", day,
                new BrigadeDto.Summary(0, 0, 0, 0, 0, 0, null, null, List.of()), List.of()));

        mvc.perform(get("/api/brigades").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agency").value("BKM"))
                .andExpect(jsonPath("$.brigades[0].brigade").value("103-05"));
        mvc.perform(get("/api/brigades/103-05/day").param("date", "2026-11-18")
                        .header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brigade").value("103-05"));
        mvc.perform(get("/api/brigades/103-05/day")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadTheNetworkCoverage() throws Exception {
        given(networkCoverageService.coverage()).willReturn(new NetworkCoverageDto(List.of(), 10,
                new NetworkCoverageDto.Place("Białystok", new NetworkCoverageDto.Shape(List.of(), List.of()), 102),
                List.of(new NetworkCoverageDto.RadiusCoverage(400, new NetworkCoverageDto.Coverage(102, 75, 0.74),
                        new NetworkCoverageDto.Coverage(81, 66, 0.82))),
                List.of(), 21, List.of(), 5, List.of(), 0.1, new NetworkCoverageDto.Population(289_217, "2025-12-31", "GUS"),
                new NetworkCoverageDto.Sources("OSM", "2026-11-20", "city"),
                new NetworkCoverageDto.Belt(5, 20, List.of(), List.of())));
        mvc.perform(get("/api/network-coverage").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverages[0].city.share").value(0.74));
        mvc.perform(get("/api/network-coverage")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanReadTheCityBoundaries() throws Exception {
        mvc.perform(get("/api/boundaries").header("Authorization", bearer("guest", "GUEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city[0].length()").value(2))
                .andExpect(jsonPath("$.centre.length()").value(CityBoundaries.CENTRE.length))
                .andExpect(jsonPath("$.lineCentre.length()").value(9));
        mvc.perform(get("/api/boundaries")).andExpect(status().isUnauthorized());
    }

    private double loginCount(String outcome) {
        var counter = meterRegistry.find(AuthController.LOGINS_METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private String bearer(String user, String role) {
        return "Bearer " + jwtService.generateToken(user, role);
    }
}
