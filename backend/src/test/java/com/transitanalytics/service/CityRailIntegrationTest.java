package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.gtfs.GtfsImported;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DbIntegrationTest
@Transactional
class CityRailIntegrationTest {

    /** A Wednesday. */
    private static final Instant NOW = Instant.parse("2099-11-25T11:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    private long polregio;
    private long intercity;

    @BeforeEach
    void timetable() {
        for (String table : List.of("trip", "route", "agency", "stop", "calendar_date", "shape_point")) {
            jdbc.update("DELETE FROM " + table + " WHERE feed = 'rail'");
        }
        polregio = route("PR");
        intercity = route("IC");
        stop("HUB", "Białystok", 53.1336, 23.1354, null);
        stop("HUB-1", "Białystok", 53.1336, 23.1354, "HUB");
        stop("WAS", "Wasilków", 53.1979, 23.1768, null);
        stop("CZB", "Czarny Blok", 53.2485, 23.2092, null);
        stop("SOK", "Sokółka", 53.4, 23.5, null);
        stop("KUZ", "Kuźnica", 53.5, 23.65, null);
        stop("LAP", "Łapy", 52.99, 22.8836, null);
        // North-east to Kuźnica and (another shape, the same track) to Sokółka; south-west to Łapy; through both.
        shape("KUZ", new double[][] {{53.1336, 23.1354}, {53.1979, 23.1768}, {53.2485, 23.2092}, {53.4, 23.5},
                {53.5, 23.65}});
        shape("SOK", new double[][] {{53.4, 23.5}, {53.2486, 23.2093}, {53.1979, 23.1768}, {53.1336, 23.1354}});
        shape("THROUGH", new double[][] {{52.99, 22.8836}, {53.1026, 23.0804}, {53.1336, 23.1354},
                {53.1979, 23.1768}, {53.2485, 23.2092}, {53.5, 23.65}});
        shape("SHORT", new double[][] {{53.1336, 23.1354}, {53.1979, 23.1768}});
        day("WD", "2099-11-25");
        day("WD", "2099-11-26");
        day("SA", "2099-11-28");
        trip(polregio, "T1", "WD", "KUZ", "HUB-1", "WAS", "CZB", "SOK", "KUZ");
        // Past the small stations.
        trip(polregio, "T2", "WD", "KUZ", "HUB-1", "SOK");
        trip(polregio, "T3", "WD", "SOK", "SOK", "CZB", "WAS", "HUB-1");
        trip(polregio, "T4", "SA", "SOK", "SOK", "HUB-1");
        trip(polregio, "T5", "WD", "THROUGH", "LAP", "HUB-1", "KUZ");
        // Intercity, past the end of the network without a call there.
        trip(intercity, "I1", "WD", "KUZ", "HUB-1", "KUZ");
        // Turns back before the end.
        trip(polregio, "T7", "SA", "SHORT", "HUB-1", "WAS");
        // Without a shape: not shown.
        trip(polregio, "T6", "WD", null, "HUB-1", "LAP");
    }

    @Test
    void aRouteHasAllTrainsOnTheRailwayToItsEnd() {
        CityRailDto.Report report = service().report();

        assertThat(report.railways()).isNotEmpty().anyMatch(r -> "6".equals(r.ref()));
        assertThat(report.days()).containsExactly(
                new CityRailDto.Day("weekday", LocalDate.of(2099, 11, 25)),
                new CityRailDto.Day("saturday", LocalDate.of(2099, 11, 28)));
        assertThat(report.routes()).extracting(CityRailDto.Route::id).containsExactly("Czarny Blok", "Łapy");

        CityRailDto.Route north = report.routes().get(0);
        assertThat(north.stations()).extracting(CityRailDto.Station::name)
                .containsExactly("Białystok", "Wasilków", "Czarny Blok");
        assertThat(north.points()).as("up to the end of the network").hasSize(3);
        assertThat(north.polregio().destinations()).containsExactly("Sokółka", "Kuźnica", "Wasilków");
        // From the city: T1, T2 and T5 going on; to it: T3. On Saturday: T7, and to it T4.
        assertThat(north.polregio().departures()).containsExactly(new CityRailDto.Departures(3, 1),
                new CityRailDto.Departures(1, 1));
        assertThat(north.all().departures()).as("with the Intercity train").containsExactly(
                new CityRailDto.Departures(4, 1), new CityRailDto.Departures(1, 1));
        assertThat(north.all().destinations()).containsExactly("Kuźnica", "Sokółka", "Wasilków");

        CityRailDto.Route south = report.routes().get(1);
        assertThat(south.all().destinations()).containsExactly("Łapy");
        assertThat(south.all().departures()).containsExactly(new CityRailDto.Departures(0, 1),
                new CityRailDto.Departures(0, 0));
    }

    @Test
    void keepsTheReportUntilTheTrainsAreImportedAgain() {
        CityRailService service = service();
        CityRailDto.Report first = service.report();
        service.afterImport(new GtfsImported("bialystok", NOW));
        assertThat(service.report()).isSameAs(first);
        service.afterImport(new GtfsImported("rail", NOW));
        assertThat(service.report()).isNotSameAs(first);
    }

    @Test
    void countsNoPublicHolidayAsAnOrdinaryDay() {
        // 25 December 2026 is a Friday, the 26th a Saturday.
        assertThat(CityRailService.days(List.of(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26),
                LocalDate.of(2026, 12, 27), LocalDate.of(2026, 12, 28)))).containsExactly(
                new CityRailDto.Day("weekday", LocalDate.of(2026, 12, 28)),
                new CityRailDto.Day("sunday", LocalDate.of(2026, 12, 27)));
    }

    private CityRailService service() {
        return new CityRailService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                new CityRailProperties("HUB", List.of("CZB", "LAP")));
    }

    private long route(String agency) {
        long id = jdbc.queryForObject("INSERT INTO agency (feed, gtfs_id, name, url, timezone) "
                + "VALUES ('rail', ?, ?, 'https://example.org', 'Europe/Warsaw') RETURNING id", Long.class, agency, agency);
        return jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, agency_id, route_type) "
                + "VALUES ('rail', ?, ?, 2) RETURNING id", Long.class, agency + "_R", id);
    }

    private void stop(String id, String name, double lat, double lon, String parent) {
        jdbc.update("INSERT INTO stop (feed, gtfs_id, name, latitude, longitude, parent_station) "
                + "VALUES ('rail', ?, ?, ?, ?, ?)", id, name, lat, lon, parent);
    }

    private void shape(String id, double[][] points) {
        for (int i = 0; i < points.length; i++) {
            jdbc.update("INSERT INTO shape_point (feed, shape_id, sequence, latitude, longitude) "
                    + "VALUES ('rail', ?, ?, ?, ?)", id, i, points[i][0], points[i][1]);
        }
    }

    private void day(String service, String date) {
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) "
                + "VALUES ('rail', ?, ?::date, 1)", service, date);
    }

    private void trip(long route, String id, String service, String shape, String... stops) {
        long trip = jdbc.queryForObject("INSERT INTO trip (feed, gtfs_id, route_id, service_id, shape_id) "
                + "VALUES ('rail', ?, ?, ?, ?) RETURNING id", Long.class, id, route, service, shape);
        for (int i = 0; i < stops.length; i++) {
            String time = "%02d:00:00".formatted(8 + i);
            jdbc.update("INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time) VALUES "
                    + "(?, (SELECT id FROM stop WHERE feed = 'rail' AND gtfs_id = ?), ?, ?, ?)", trip, stops[i], i,
                    time, time);
        }
    }
}
