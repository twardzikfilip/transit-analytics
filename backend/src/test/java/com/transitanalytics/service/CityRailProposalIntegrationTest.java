package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.CityRailProposalDto;
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
class CityRailProposalIntegrationTest {

    /** A Wednesday. */
    private static final Instant NOW = Instant.parse("2099-11-25T11:00:00Z");
    private static final String DAY = "2099-11-25";

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void timetable() {
        for (String table : List.of("trip", "route", "agency", "stop", "calendar_date", "shape_point")) {
            jdbc.update("DELETE FROM " + table + " WHERE feed = 'rail'");
        }
        long polregio = route("PR");
        long intercity = route("IC");
        // As in the proposal's network (places/osm/city-rail-proposal.json), and Niewodnica beyond Klepacze.
        stop("24000", "Białystok", 53.13362, 23.13544);
        stop("279919", "Białystok Zielone Wzgórza", 53.11550, 23.10301);
        stop("24612", "Klepacze", 53.10228, 23.07984);
        stop("24596", "Niewodnica", 53.0827, 23.04576);
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) VALUES ('rail', 'WD', ?::date, 1)",
                DAY);
        // Polregio calls everywhere; the Intercity train passes Zielone Wzgórza and Klepacze, on its track.
        trip(polregio, "P1", null, "24000", "08:00", "279919", "08:03", "24612", "08:07");
        trip(intercity, "I1", "S1", "24000", "12:00", "24596", "12:10");
        double[][] track = {{53.13362, 23.13544}, {53.11550, 23.10301}, {53.10228, 23.07984}, {53.0827, 23.04576}};
        for (int i = 0; i < track.length; i++) {
            jdbc.update("INSERT INTO shape_point (feed, shape_id, sequence, latitude, longitude) VALUES "
                    + "('rail', 'S1', ?, ?, ?)", i, track[i][0], track[i][1]);
        }
    }

    @Test
    void timetablesBothSolutionsAroundTodaysTrains() {
        CityRailProposalDto.Proposal proposal = service().proposal();

        assertThat(proposal.day()).isEqualTo(LocalDate.of(2099, 11, 25));
        assertThat(proposal.rules().trainsConsidered()).isEqualTo(2);
        assertThat(proposal.corridors()).extracting(CityRailProposalDto.Corridor::id)
                .containsExactly("klepacze", "fasty", "lewickie", "wasilkow", "kuriany");
        assertThat(proposal.corridors()).filteredOn(CityRailProposalDto.Corridor::served)
                .extracting(CityRailProposalDto.Corridor::id).containsExactly("klepacze");
        assertThat(proposal.solutions()).extracting(CityRailProposalDto.Solution::id)
                .containsExactly("radial", "through");
        assertThat(proposal.solutions().get(0).lines()).hasSize(5);
        assertThat(proposal.solutions().get(1).lines()).extracting(CityRailProposalDto.Line::id)
                .containsExactly("S1", "S2", "S3");

        CityRailProposalDto.Line klepacze = proposal.solutions().get(0).lines().get(0);
        assertThat(klepacze.stations()).extracting(CityRailProposalDto.LineStation::name)
                .containsExactly("Białystok", "Białystok Zielone Wzgórza", "Klepacze");
        assertThat(klepacze.runMinutes()).as("Polregio's 3 and 4 minutes").isEqualTo(7);
        assertThat(klepacze.conflicts()).isZero();
        assertThat(klepacze.closestMinutes()).isGreaterThanOrEqualTo(10);
        assertThat(klepacze.maxIntervalMinutes()).isLessThanOrEqualTo(60);
        assertThat(klepacze.out()).extracting(t -> t.times().get(0))
                .as("clear of Polregio at 08:00 and Intercity at 12:00")
                .noneMatch(time -> time.compareTo("07:51") >= 0 && time.compareTo("08:09") <= 0)
                .noneMatch(time -> time.compareTo("11:51") >= 0 && time.compareTo("12:09") <= 0);

        CityRailProposalDto.Line through = proposal.solutions().get(1).lines().get(0);
        assertThat(through.stations().get(0).name()).isEqualTo("Wasilków");
        assertThat(through.stations()).extracting(CityRailProposalDto.LineStation::name).contains("Białystok",
                "Białystok Białostoczek", "Ignatki-Osiedle");
        assertThat(through.stations().get(through.stations().size() - 1).name()).isEqualTo("Lewickie");
        assertThat(through.vehicles()).isPositive();
    }

    @Test
    void keepsTheProposalUntilTheTrainsAreImported() {
        CityRailProposal service = service();
        CityRailProposalDto.Proposal first = service.proposal();
        service.afterImport(new GtfsImported("bialystok", NOW));
        assertThat(service.proposal()).isSameAs(first);
        service.afterImport(new GtfsImported("rail", NOW));
        assertThat(service.proposal()).isNotSameAs(first);
    }

    @Test
    void hasNoTimetablesWithoutTrains() {
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'rail'");

        CityRailProposalDto.Proposal proposal = service().proposal();

        assertThat(proposal.day()).isNull();
        assertThat(proposal.solutions()).isEmpty();
        assertThat(proposal.corridors()).hasSize(5).noneMatch(CityRailProposalDto.Corridor::served);
    }

    private CityRailProposal service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CityRailProperties network = new CityRailProperties("24000", List.of("24596"));
        return new CityRailProposal(jdbc, clock, new CityRailService(jdbc, clock, network), false);
    }

    private long route(String agency) {
        long id = jdbc.queryForObject("INSERT INTO agency (feed, gtfs_id, name, url, timezone) "
                + "VALUES ('rail', ?, ?, 'https://example.org', 'Europe/Warsaw') RETURNING id", Long.class, agency,
                agency);
        return jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, agency_id, short_name, long_name, route_type) "
                + "VALUES ('rail', ?, ?, '', ?, 2) RETURNING id", Long.class, agency + "_R", id, agency);
    }

    private void stop(String id, String name, double lat, double lon) {
        jdbc.update("INSERT INTO stop (feed, gtfs_id, name, latitude, longitude) VALUES ('rail', ?, ?, ?, ?)", id,
                name, lat, lon);
    }

    /** @param calls station id and "HH:MM", in turn */
    private void trip(long route, String id, String shape, String... calls) {
        long trip = jdbc.queryForObject("INSERT INTO trip (feed, gtfs_id, route_id, service_id, shape_id) "
                + "VALUES ('rail', ?, ?, 'WD', ?) RETURNING id", Long.class, id, route, shape);
        for (int i = 0; i < calls.length; i += 2) {
            String time = calls[i + 1] + ":00";
            jdbc.update("INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time) VALUES "
                    + "(?, (SELECT id FROM stop WHERE feed = 'rail' AND gtfs_id = ?), ?, ?, ?)", trip, calls[i], i,
                    time, time);
        }
    }
}
