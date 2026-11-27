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
class RailBusComparisonIntegrationTest {

    /** A Wednesday. */
    private static final Instant NOW = Instant.parse("2099-11-25T11:00:00Z");
    private static final String DAY = "2099-11-25";

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void timetables() {
        for (String table : List.of("trip", "route", "agency", "stop", "calendar_date", "shape_point")) {
            jdbc.update("DELETE FROM " + table + " WHERE feed IN ('rail', 'bialystok')");
        }
        // Trains: Białystok and Starosielce in the city, Wasilków outside it.
        long polregio = route("rail", "PR", 2);
        long intercity = route("rail", "IC", 2);
        stop("rail", "HUB", "Białystok", 53.1336, 23.1354, null);
        stop("rail", "HUB-1", "Białystok", 53.1336, 23.1354, "HUB");
        stop("rail", "STA", "Białystok Starosielce", 53.12134, 23.08656, null);
        stop("rail", "WAS", "Wasilków", 53.1979, 23.1768, null);
        day("rail", "WD");
        trip("rail", polregio, "T1", "WD", "15801", "WAS", "07:40", "HUB-1", "08:00", "STA", "08:06");
        trip("rail", intercity, "I1", "WD", "1600", "HUB-1", "09:00", "STA", "09:03");

        // Buses: two stops by the main station (one too far beyond the nearest), one by Starosielce.
        long bkm = route("bialystok", "BKM", 3);
        stop("bialystok", "B1", "Centrum Przydworcowe", 53.1338, 23.1356, null);
        stop("bialystok", "B2", "Dworzec PKS", 53.1370, 23.1390, null);
        stop("bialystok", "S1", "Starosielce PKP/Pętla", 53.1215, 23.0868, null);
        stop("bialystok", "S2", "Wrocławska", 53.1225, 23.0868, null);
        day("bialystok", "WD");
        // Off-peak; in the morning peak, faster but not counted; and back with a change at a stop nearby.
        trip("bialystok", bkm, "4", "WD", null, "B1", "09:00", "S1", "09:20");
        trip("bialystok", bkm, "8", "WD", null, "B1", "07:00", "S1", "07:10");
        trip("bialystok", bkm, "14", "WD", null, "S1", "10:00", "B2", "10:12");
        trip("bialystok", bkm, "21", "WD", null, "S2", "12:00", "B2", "12:10");
        trip("bialystok", bkm, "2", "WD", null, "B2", "12:20", "B1", "12:25");
    }

    @Test
    void comparesTheFastestTrainWithTheFastestOffPeakBus() {
        CityRailDto.Comparison comparison = service().comparison();

        assertThat(comparison.day()).isEqualTo(LocalDate.of(2099, 11, 25));
        assertThat(comparison.maxChanges()).isEqualTo(2);
        assertThat(comparison.stations()).extracting(CityRailDto.ComparedStation::name)
                .as("in the city only, the hub first").containsExactly("Białystok", "Białystok Starosielce");
        assertThat(comparison.stations().get(0).stops()).extracting(CityRailDto.NearStop::name)
                .as("the nearest; the next is over 200 m further").containsExactly("Centrum Przydworcowe");
        assertThat(comparison.stations().get(1).stops()).extracting(CityRailDto.NearStop::name)
                .containsExactly("Starosielce PKP/Pętla", "Wrocławska");

        CityRailDto.Pair out = comparison.pairs().get(0);
        assertThat(out.from()).isEqualTo("Białystok");
        assertThat(out.to()).isEqualTo("Białystok Starosielce");
        assertThat(out.train()).as("Polregio's only").isEqualTo(new CityRailDto.Journey(6, "08:00", "08:06",
                List.of(new CityRailDto.Leg("15801", "Białystok", "Białystok Starosielce", "08:00", "08:06"))));
        assertThat(out.bus()).as("off-peak only").isEqualTo(new CityRailDto.Journey(20, "09:00", "09:20",
                List.of(new CityRailDto.Leg("4", "Centrum Przydworcowe", "Starosielce PKP/Pętla", "09:00",
                        "09:20"))));

        CityRailDto.Pair back = comparison.pairs().get(1);
        assertThat(back.train()).isNull();
        assertThat(back.bus().minutes()).as("from Wrocławska, faster than line 14 and its change").isEqualTo(25);
        assertThat(back.bus().legs()).extracting(CityRailDto.Leg::line).containsExactly("21", "2");
    }

    @Test
    void keepsTheComparisonUntilATimetableIsImported() {
        RailBusComparison service = service();
        CityRailDto.Comparison first = service.comparison();
        assertThat(service.comparison()).isSameAs(first);
        service.afterImport(new GtfsImported("bialystok", NOW));
        assertThat(service.comparison()).isNotSameAs(first).isEqualTo(first);
    }

    @Test
    void isEmptyWithoutTrains() {
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'rail'");

        CityRailDto.Comparison comparison = service().comparison();

        assertThat(comparison.day()).isNull();
        assertThat(comparison.pairs()).isEmpty();
    }

    private RailBusComparison service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CityRailProperties network = new CityRailProperties("HUB", List.of("WAS"));
        return new RailBusComparison(jdbc, clock, new CityRailService(jdbc, clock, network), network, false);
    }

    private long route(String feed, String agency, int type) {
        long id = jdbc.queryForObject("INSERT INTO agency (feed, gtfs_id, name, url, timezone) "
                + "VALUES (?, ?, ?, 'https://example.org', 'Europe/Warsaw') RETURNING id", Long.class, feed, agency,
                agency);
        return jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, agency_id, short_name, long_name, route_type) "
                + "VALUES (?, ?, ?, '', ?, ?) RETURNING id", Long.class, feed, agency + "_R", id, agency, type);
    }

    private void stop(String feed, String id, String name, double lat, double lon, String parent) {
        jdbc.update("INSERT INTO stop (feed, gtfs_id, name, latitude, longitude, parent_station) "
                + "VALUES (?, ?, ?, ?, ?, ?)", feed, id, name, lat, lon, parent);
    }

    private void day(String feed, String service) {
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) VALUES (?, ?, ?::date, 1)",
                feed, service, DAY);
    }

    /**
     * A trip with its line (a train's number) as its short name, for trains, or as its route's name, for buses.
     *
     * @param calls stop id and "HH:MM", in turn
     */
    private void trip(String feed, long route, String id, String service, String number, String... calls) {
        long routeId = "rail".equals(feed) ? route : jdbc.queryForObject("""
                INSERT INTO route (feed, gtfs_id, agency_id, short_name, long_name, route_type)
                SELECT feed, ?, agency_id, ?, '', 3 FROM route WHERE id = ? RETURNING id""", Long.class,
                "line-" + id, id, route);
        long trip = jdbc.queryForObject("INSERT INTO trip (feed, gtfs_id, route_id, service_id, short_name) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, feed, id, routeId, service, number);
        for (int i = 0; i < calls.length; i += 2) {
            String time = calls[i + 1] + ":00";
            jdbc.update("INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time) VALUES "
                    + "(?, (SELECT id FROM stop WHERE feed = ? AND gtfs_id = ?), ?, ?, ?)", trip, feed, calls[i], i,
                    time, time);
        }
    }
}
