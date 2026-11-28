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
        jdbc.update("DELETE FROM city_rail_proposal_variant");
        for (String table : List.of("trip", "route", "agency", "stop", "calendar_date", "shape_point")) {
            jdbc.update("DELETE FROM " + table + " WHERE feed = 'rail'");
        }
        long polregio = route("PR");
        long intercity = route("IC");
        // As in the proposal's network (places/osm/city-rail-proposal.json).
        stop("24000", "Białystok", 53.13362, 23.13544);
        stop("279919", "Białystok Zielone Wzgórza", 53.11550, 23.10301);
        stop("24612", "Klepacze", 53.10228, 23.07984);
        stop("24596", "Niewodnica", 53.0827, 23.04576);
        stop("24364", "Lewickie", 53.02788, 23.12482);
        stop("24380", "Hołówki Duże", 52.97137, 23.09528);
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) VALUES ('rail', 'WD', ?::date, 1)",
                DAY);
        // Polregio calls everywhere; the Intercity train passes Zielone Wzgórza and Klepacze, on its track.
        trip(polregio, "P1", null, "24000", "08:00", "279919", "08:03", "24612", "08:07");
        trip(intercity, "I1", "S1", "24000", "12:00", "24596", "12:10");
        // Past Juchnowiec Dolny, the end of the line to it: its run time from Lewickie is shared out of this one.
        trip(polregio, "P2", null, "24364", "09:00", "24380", "09:10");
        double[][] track = {{53.13362, 23.13544}, {53.11550, 23.10301}, {53.10228, 23.07984}, {53.0827, 23.04576}};
        for (int i = 0; i < track.length; i++) {
            jdbc.update("INSERT INTO shape_point (feed, shape_id, sequence, latitude, longitude) VALUES "
                    + "('rail', 'S1', ?, ?, ?)", i, track[i][0], track[i][1]);
        }
    }

    @Test
    void timetablesBothSolutionsAtEachFrequencyAroundTodaysTrains() {
        CityRailProposalDto.Proposal proposal = service().proposal();

        assertThat(proposal.day()).isEqualTo(LocalDate.of(2099, 11, 25));
        assertThat(proposal.computedAt()).isEqualTo(NOW);
        assertThat(proposal.rules().trainsConsidered()).isEqualTo(2);
        assertThat(proposal.corridors()).extracting(CityRailProposalDto.Corridor::id)
                .containsExactly("niewodnica", "dobrzyniewo", "juchnowiec", "wasilkow", "henrykowo");
        assertThat(proposal.corridors()).filteredOn(CityRailProposalDto.Corridor::served)
                .extracting(CityRailProposalDto.Corridor::id).containsExactly("niewodnica");
        assertThat(proposal.solutions()).extracting(CityRailProposalDto.Solution::id)
                .containsExactly("radial", "through");
        for (CityRailProposalDto.Solution solution : proposal.solutions()) {
            assertThat(solution.variants()).extracting(CityRailProposalDto.Variant::frequency)
                    .containsExactly("1tp2h", "1tph", "2tph", "3tph");
            for (CityRailProposalDto.Variant variant : solution.variants()) {
                assertThat(variant.vehicles()).isEqualTo(variant.lines().stream()
                        .mapToInt(CityRailProposalDto.Line::vehicles).sum());
                assertThat(variant.lines()).allSatisfy(line ->
                        assertThat(line.maxIntervalMinutes()).isLessThanOrEqualTo(variant.periodMinutes()));
            }
        }
        assertThat(variant(proposal, "radial", "1tph").lines()).hasSize(5);
        assertThat(variant(proposal, "through", "1tph").lines()).extracting(CityRailProposalDto.Line::id)
                .containsExactly("S1", "S2", "S3");
        assertThat(variant(proposal, "radial", "3tph").vehicles())
                .isGreaterThan(variant(proposal, "radial", "1tp2h").vehicles());

        CityRailProposalDto.Line niewodnica = variant(proposal, "radial", "1tph").lines().get(0);
        assertThat(niewodnica.stations()).extracting(CityRailProposalDto.LineStation::name)
                .containsExactly("Białystok", "Białystok Zielone Wzgórza", "Klepacze", "Niewodnica");
        assertThat(niewodnica.runMinutes()).as("Polregio's 3 and 4 minutes, and on to Niewodnica").isGreaterThan(7);
        assertThat(niewodnica.conflicts()).isZero();
        assertThat(niewodnica.closestMinutes()).isGreaterThanOrEqualTo(10);
        assertThat(niewodnica.out()).filteredOn(t -> t.kind().equals("city")).extracting(t -> t.times().get(0))
                .as("clear of Polregio at 08:00 and Intercity at 12:00")
                .noneMatch(time -> time.compareTo("07:51") >= 0 && time.compareTo("08:09") <= 0)
                .noneMatch(time -> time.compareTo("11:51") >= 0 && time.compareTo("12:09") <= 0);
        // Today's trains beside the line's own, in order, each on the part of the line it runs on.
        assertThat(niewodnica.out()).filteredOn(t -> t.kind().equals("PR")).singleElement().satisfies(t -> {
            assertThat(t.label()).isEqualTo("P1");
            assertThat(t.times()).containsExactly("08:00", "08:03", "08:07", null);
        });
        assertThat(niewodnica.out()).filteredOn(t -> t.kind().equals("IC")).singleElement().satisfies(t -> {
            assertThat(t.label()).isEqualTo("I1");
            assertThat(t.times()).containsExactly("12:00", "|", "|", "12:10");
        });
        assertThat(niewodnica.out()).extracting(t -> t.times().get(0)).isSorted();
        assertThat(niewodnica.back()).noneMatch(t -> !t.kind().equals("city"));

        CityRailProposalDto.Line through = variant(proposal, "through", "1tph").lines().get(0);
        assertThat(through.stations().get(0).name()).isEqualTo("Wasilków");
        assertThat(through.stations()).extracting(CityRailProposalDto.LineStation::name).contains("Białystok",
                "Białystok Białostoczek", "Ignatki-Osiedle");
        assertThat(through.stations().get(through.stations().size() - 1).name()).isEqualTo("Juchnowiec Dolny");

        // Lewickie to Hołówki Duże in 10 minutes: to Juchnowiec Dolny, a third of the way, its share and the stop.
        CityRailProposalDto.Line juchnowiec = variant(proposal, "radial", "1tph").lines().get(2);
        List<String> names = juchnowiec.stations().stream().map(CityRailProposalDto.LineStation::name).toList();
        assertThat(names.subList(names.size() - 2, names.size())).containsExactly("Lewickie", "Juchnowiec Dolny");
        assertThat(juchnowiec.km()).isEqualTo(16.66);
        CityRailProposalDto.Trip first = juchnowiec.out().stream().filter(t -> t.kind().equals("city")).findFirst()
                .orElseThrow();
        assertThat(minutes(first.times().get(names.size() - 1)) - minutes(first.times().get(names.size() - 2)))
                .isEqualTo(4);
        assertThat(through.vehicles()).isPositive();

        CityRailProposalDto.Line dobrzyniewo = variant(proposal, "through", "1tph").lines().get(1);
        assertThat(dobrzyniewo.stations().get(0).name()).isEqualTo("Dobrzyniewo Duże");
        assertThat(dobrzyniewo.stations().get(dobrzyniewo.stations().size() - 1).name()).isEqualTo("Henrykowo");
        int fasty = dobrzyniewo.stations().stream().map(CityRailProposalDto.LineStation::name).toList()
                .indexOf("Fasty");
        assertThat(dobrzyniewo.stations().get(fasty).kind()).isEqualTo("passed");
        assertThat(dobrzyniewo.stations().get(fasty + 1).name()).isEqualTo("Fasty Osada");
        assertThat(dobrzyniewo.out()).allSatisfy(t -> assertThat(t.times().get(fasty)).isEqualTo("|"));
    }

    @Test
    void storesTheProposalOnceAndKeepsItAfterImports() {
        CityRailProposalDto.Proposal first = service().proposal();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM city_rail_proposal_variant", Integer.class))
                .isEqualTo(8);

        // The trains change: the stored proposal stays, read back as it was worked out.
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'rail'");
        CityRailProposal later = service();
        later.afterImport(new GtfsImported("rail", NOW));
        CityRailProposalDto.Proposal read = later.proposal();
        assertThat(read.day()).isEqualTo(first.day());
        assertThat(read).usingRecursiveComparison().isEqualTo(first);
        later.afterImport(new GtfsImported("rail", NOW));
        assertThat(later.proposal()).isSameAs(read);
    }

    @Test
    void worksItOutAgainForAnotherNetwork() {
        service().proposal();
        jdbc.update("UPDATE city_rail_proposal_variant SET network_hash = 'another', day = '2000-01-01'");

        assertThat(service().proposal().day()).isEqualTo(LocalDate.of(2099, 11, 25));
        assertThat(jdbc.queryForList("SELECT DISTINCT network_hash FROM city_rail_proposal_variant", String.class))
                .singleElement().isNotEqualTo("another");
    }

    @Test
    void hasNoTimetablesWithoutTrains() {
        jdbc.update("DELETE FROM calendar_date WHERE feed = 'rail'");
        CityRailService rail = rail();
        CityRailProposal service = new CityRailProposal(jdbc, clock(), rail, false);

        CityRailProposalDto.Proposal proposal = service.proposal();

        assertThat(proposal.day()).isNull();
        assertThat(proposal.solutions()).isEmpty();
        assertThat(proposal.corridors()).hasSize(5).noneMatch(CityRailProposalDto.Corridor::served);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM city_rail_proposal_variant", Integer.class)).isZero();
        // Worked out once the trains are imported.
        jdbc.update("INSERT INTO calendar_date (feed, service_id, date, exception_type) VALUES ('rail', 'WD', ?::date, 1)",
                DAY);
        rail.afterImport(new GtfsImported("rail", NOW));
        service.afterImport(new GtfsImported("rail", NOW));
        assertThat(service.proposal().solutions()).hasSize(2);
    }

    private static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }

    private static CityRailProposalDto.Variant variant(CityRailProposalDto.Proposal p, String solution,
                                                       String frequency) {
        return p.solutions().stream().filter(s -> s.id().equals(solution)).findFirst().orElseThrow().variants()
                .stream().filter(v -> v.frequency().equals(frequency)).findFirst().orElseThrow();
    }

    private CityRailProposal service() {
        return new CityRailProposal(jdbc, clock(), rail(), false);
    }

    private CityRailService rail() {
        return new CityRailService(jdbc, clock(), new CityRailProperties("24000", List.of("24596")));
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
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
        long trip = jdbc.queryForObject("INSERT INTO trip (feed, gtfs_id, route_id, service_id, shape_id, short_name) "
                + "VALUES ('rail', ?, ?, 'WD', ?, ?) RETURNING id", Long.class, id, route, shape, id + " (P1)");
        for (int i = 0; i < calls.length; i += 2) {
            String time = calls[i + 1] + ":00";
            jdbc.update("INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time) VALUES "
                    + "(?, (SELECT id FROM stop WHERE feed = 'rail' AND gtfs_id = ?), ?, ?, ?)", trip, calls[i], i,
                    time, time);
        }
    }
}
