package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.AgencyDto;
import com.transitanalytics.dto.CalendarDateDto;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.dto.ShapePointDto;
import com.transitanalytics.dto.StopDto;
import com.transitanalytics.dto.StopTimeDto;
import com.transitanalytics.dto.TripDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

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

    @Autowired
    AgencyService agencies;

    @Autowired
    StopTimeService stopTimes;

    @Autowired
    ShapePointService shapePoints;

    @Autowired
    CalendarDateService calendarDates;

    @Test
    void editsATimetableThroughEveryResource() {
        var page = PageRequest.of(0, 10);
        var agency = agencies.create(new AgencyDto.Request("IT-AG", "IT Agency", "https://it.example", "Europe/Warsaw",
                "pl", "+48 1", "https://it.example/fares", "it@example.com"));
        var route = routes.create(new RouteDto.Request("IT-R9", agency.id(), "IT9", "Rynek – Dworzec", null, (short) 3,
                null, "#006963", "#FFFFFF", 9, null));
        assertThat(route.agencyName()).isEqualTo("IT Agency");
        assertThat(route.subtype()).as("from the name").isEqualTo(RouteSubtype.URBAN);

        var rynek = stops.create(new StopDto.Request("IT-S9", "901", "Rynek", "Market square", 53.13, 23.16, "1",
                null, (short) 0, null, (short) 1, "A"));
        var dworzec = stops.create(new StopDto.Request("IT-S8", "902", "Dworzec", null, 53.14, 23.17, "1", null, null,
                null, null, null));
        var trip = trips.create(new TripDto.Request("IT9-01_X", route.id(), "IT_EDIT", "Dworzec", null, (short) 0,
                "IT9-01", "IT-SH9", (short) 1, (short) 2));
        assertThat(trip.routeName()).isEqualTo("IT9");
        var first = stopTimes.create(new StopTimeDto.Request(trip.id(), rynek.id(), 1, "06:00:00", "06:00:00", null,
                (short) 0, (short) 1, 0.0, (short) 1));
        stopTimes.create(new StopTimeDto.Request(trip.id(), dworzec.id(), 2, "06:07:00", "06:07:00", null, null, null,
                1.4, null));
        assertThat(first.stopName()).isEqualTo("Rynek");
        assertThat(first.tripHeadsign()).isEqualTo("Dworzec");
        shapePoints.create(new ShapePointDto.Request("IT-SH9", 1, 53.13, 23.16, 0.0));
        var date = calendarDates.create(new CalendarDateDto.Request("IT_EDIT", LocalDate.of(2099, 3, 2), (short) 1));

        assertThat(stopTimes.findAll(new StopTimeDto.Filter(trip.id(), null), page).getContent())
                .extracting(StopTimeDto.Response::stopName).containsExactlyInAnyOrder("Rynek", "Dworzec");
        assertThat(shapePoints.findAll(new ShapePointDto.Filter("IT-SH9"), page).getTotalElements()).isEqualTo(1);
        assertThat(calendarDates.findAll(new CalendarDateDto.Filter("IT_EDIT"), page).getContent())
                .extracting(CalendarDateDto.Response::date).containsExactly(LocalDate.of(2099, 3, 2));

        var renamed = stops.update(rynek.id(), new StopDto.Request("IT-S9", "901", "Rynek Kościuszki", null, 53.13,
                23.16, "2", null, null, null, null, null));
        assertThat(renamed.zoneId()).isEqualTo("2");
        assertThat(stopTimes.findById(first.id()).stopName()).isEqualTo("Rynek Kościuszki");
        assertThat(agencies.update(agency.id(), new AgencyDto.Request("IT-AG", "IT Transit", "https://it.example",
                "Europe/Warsaw", null, null, null, null)).name()).isEqualTo("IT Transit");
        assertThat(trips.update(trip.id(), new TripDto.Request("IT9-01_X", route.id(), "IT_EDIT", "Rynek", null,
                (short) 1, null, null, null, null)).direction()).isEqualTo((short) 1);

        calendarDates.delete(date.id());
        assertThatThrownBy(() -> calendarDates.findById(date.id())).isInstanceOf(NotFoundException.class);
    }
}
