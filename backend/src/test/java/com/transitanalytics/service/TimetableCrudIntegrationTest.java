package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.dto.StopDto;
import com.transitanalytics.dto.TripDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** List filters (Specs), paging and lookups of the timetable CRUD services; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class TimetableCrudIntegrationTest {

    @Autowired
    RouteService routes;

    @Autowired
    TripService trips;

    @Autowired
    StopService stops;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void filtersRoutesByTextTypeAndSubtype() {
        var timetable = new TimetableFixture(jdbc, "IT_CRUD");
        timetable.route("IT-Q1", "ITQ_A", "urban");
        timetable.route("IT-Q2", "ITQXA", "night_urban");
        var page = PageRequest.of(0, 10, Sort.by("longName"));

        assertThat(routes.findAll(new RouteDto.Filter("itq", null, null), page).getContent())
                .as("case-insensitive").extracting(RouteDto.Response::longName).containsExactly("ITQXA", "ITQ_A");
        // "_" is a literal underscore, not the SQL wildcard for any character: "q_a" doesn't match "ITQXA".
        assertThat(routes.findAll(new RouteDto.Filter("q_a", null, null), page).getContent())
                .extracting(RouteDto.Response::longName).containsExactly("ITQ_A");
        assertThat(routes.findAll(new RouteDto.Filter("itq", (short) 3, RouteSubtype.NIGHT_URBAN), page).getContent())
                .extracting(RouteDto.Response::longName).containsExactly("ITQXA");
        assertThat(routes.findAll(new RouteDto.Filter("itq", (short) 0, null), page).getContent())
                .as("no tram routes").isEmpty();
    }

    @Test
    void filtersTripsByRouteDirectionAndHeadsignAndStopsByZone() {
        var timetable = new TimetableFixture(jdbc, "IT_CRUD");
        long route = timetable.route("IT-T1", "IT-T1");
        timetable.stop("IT-TS0", "Tee Zero(1)", "2", 53.1);
        timetable.stop("IT-TS1", "Tee One(2)", "2", 53.2);
        timetable.trip(route, "IT-T1-01_1", 0, "Northwards", null, "06:00", 5, "IT-TS0", "IT-TS1");
        timetable.trip(route, "IT-T1-01_2", 1, "Southwards", null, "06:10", 5, "IT-TS1", "IT-TS0");
        var page = PageRequest.of(0, 10);

        assertThat(trips.findAll(new TripDto.Filter(route, null, null, null), page).getTotalElements()).isEqualTo(2);
        assertThat(trips.findAll(new TripDto.Filter(route, "IT_CRUD", (short) 1, null), page).getContent())
                .extracting(TripDto.Response::headsign).containsExactly("Southwards");
        assertThat(trips.findAll(new TripDto.Filter(route, null, null, "NORTH"), page).getContent())
                .extracting(TripDto.Response::gtfsId).containsExactly("IT-T1-01_1");
        assertThat(stops.findAll(new StopDto.Filter("tee", "2"), page).getTotalElements()).isEqualTo(2);
        assertThat(stops.findAll(new StopDto.Filter("tee", "1"), page).getTotalElements()).isZero();
    }

    @Test
    void pagesAndLooksUp() {
        var timetable = new TimetableFixture(jdbc, "IT_CRUD");
        long id = timetable.route("IT-P1", "ITPAGE1");
        timetable.route("IT-P2", "ITPAGE2");
        timetable.route("IT-P3", "ITPAGE3");

        var second = routes.findAll(new RouteDto.Filter("itpage", null, null),
                PageRequest.of(1, 2, Sort.by("longName")));
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent()).extracting(RouteDto.Response::longName).containsExactly("ITPAGE3");
        assertThat(routes.findById(id).longName()).isEqualTo("ITPAGE1");
        assertThatThrownBy(() -> routes.findById(-1L)).isInstanceOf(NotFoundException.class);
    }
}
