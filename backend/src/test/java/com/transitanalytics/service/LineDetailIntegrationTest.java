package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.classification.ClassificationConfigs;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.LineDetailDto;
import com.transitanalytics.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A small line in a weekday timetable of 2099, with recorded positions; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class LineDetailIntegrationTest {

    private static final String SERVICE = "IT_W";
    /** A Monday. */
    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired
    LineDetailService lines;

    @Autowired
    JdbcTemplate jdbc;

    TimetableFixture timetable;
    long route;

    @BeforeEach
    void timetable() {
        timetable = new TimetableFixture(jdbc, SERVICE);
        route = timetable.route("IT-177", "177", "suburban");
        // A (zone 1, km 0) – B (1, km 1) – C (2, km 3) – D (2, km 4); the longer variant goes on to E (2, km 6).
        // About 2 km apart, so each is its own place.
        timetable.stop("IT-A", "Alfa(901)", "1", 53.10);
        timetable.stop("IT-B", "Beta(902)", "1", 53.12);
        timetable.stop("IT-C", "Gamma(903)", "2", 53.14);
        timetable.stop("IT-D", "Delta(904)", "2", 53.16);
        timetable.stop("IT-E", "Epsilon(905)", "2", 53.18);
        timetable.trip(route, "IT-177-01_1", 0, "Delta", "IT-S0", "06:00", 10, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        timetable.trip(route, "IT-177-01_2", 1, "Alfa", "IT-S1", "06:15", 10, "IT-D:0", "IT-C:1", "IT-B:3", "IT-A:4");
        timetable.trip(route, "IT-177-01_3", 0, "Epsilon", "IT-SL", "07:30", 15,
                "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4", "IT-E:6");
        timetable.trip(route, "IT-177-02_1", 0, "Delta", "IT-S0", "06:05", 7, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        timetable.trip(route, "IT-177-01_4", 1, "Alfa", "IT-SL1", "07:50", 15,
                "IT-E:0", "IT-D:2", "IT-C:3", "IT-B:5", "IT-A:6");
        timetable.trip(route, "IT-177-02_2", 0, "Delta", "IT-S0", "07:00", 10, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        // The last run of brigade 02 ends at B, which is not where buses turn round: a pull-in to the depot.
        timetable.trip(route, "IT-177-02_3", 1, "Beta", "IT-SP", "07:15", 5, "IT-D:0", "IT-C:1", "IT-B:3");
        timetable.trip(route, "IT-177-03_1", 0, "Epsilon", "IT-SL", "09:00", 15,
                "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4", "IT-E:6");
        for (String shape : new String[]{"IT-S0", "IT-S1", "IT-SL", "IT-SL1", "IT-SP"}) {
            timetable.shape(shape);
        }
    }

    @Test
    void describesVariantsZoneTwoAndBrigadesFromTheTimetable() {
        LineDetailDto.Detail d = lines.detail("IT-177");
        assertThat(d.recorded()).as("a line in the timetable has its timetable routes").isNull();

        assertThat(d.subtype()).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(d.serviceId()).isEqualTo(SERVICE);
        // The depots (map/depots.yml, with their outlines), and where the brigades start and end their day, each
        // with the way to or from the depot: the nearest, as no company runs this line; straight in tests.
        assertThat(d.operator()).isNull();
        assertThat(d.depots()).extracting(LineDetailDto.Depot::code).containsExactly("KPKM", "KPK", "KZK");
        assertThat(d.depots().get(1).outlines()).hasSize(2);
        assertThat(d.depotLinks()).extracting(l -> l.kind() + " " + l.stop() + " " + l.brigades())
                .containsExactly("pull_out Alfa 3", "pull_in Alfa 1", "pull_in Beta 1", "pull_in Epsilon 1");
        assertThat(d.depotLinks()).allSatisfy(l -> {
            assertThat(l.depot()).isIn("KPKM", "KPK");
            assertThat(l.routed()).isFalse();
            assertThat(l.path()).hasSize(2);
        });
        assertThat(d.depotLinks().get(0).path().get(1)).containsExactly(53.10, 23.1);
        assertThat(d.variants()).extracting(LineDetailDto.Variant::headsign, LineDetailDto.Variant::kind,
                        LineDetailDto.Variant::km, LineDetailDto.Variant::tripsPerDay)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Delta", "main", 4.0, 3),
                        org.assertj.core.groups.Tuple.tuple("Epsilon", "longer", 6.0, 2),
                        org.assertj.core.groups.Tuple.tuple("Alfa", "main", 4.0, 1),
                        org.assertj.core.groups.Tuple.tuple("Alfa", "longer", 6.0, 1),
                        org.assertj.core.groups.Tuple.tuple("Beta", "depot", 3.0, 1));
        LineDetailDto.Variant main = d.variants().get(0);
        assertThat(main.firstStop()).isEqualTo("Alfa");
        assertThat(main.points()).hasSize(2);
        assertThat(main.shortestMin()).isEqualTo(7);

        assertThat(d.base()).isEqualTo(new LineDetailDto.Base(4.0, 7, 10, 24.0, 34.3));
        assertThat(main.maxSpeedKmh()).isEqualTo(34.3);
        // B (zone 1) → C (zone 2) counts half: 1 km, plus C → D: 1 km; the longer variants add D – E (2 km).
        // The depot run (to B) is left out.
        assertThat(d.zone2()).extracting(LineDetailDto.Zone2::kind, LineDetailDto.Zone2::km, LineDetailDto.Zone2::stops)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("main", 2.0, 2),
                        org.assertj.core.groups.Tuple.tuple("longer", 4.0, 3),
                        org.assertj.core.groups.Tuple.tuple("main", 2.0, 2),
                        org.assertj.core.groups.Tuple.tuple("longer", 4.0, 3));

        // E is an end stop because brigade 01 turns round there; B is only reached by the depot run.
        assertThat(d.endStops()).extracting(LineDetailDto.EndStop::name).containsExactly("Alfa", "Delta", "Epsilon");
        assertThat(d.depotStops()).extracting(LineDetailDto.EndStop::name).containsExactly("Beta");
        assertThat(d.depotRuns()).containsExactly(new LineDetailDto.DepotRun("IT-177-02", 7 * 3600 + 15 * 60,
                7 * 3600 + 20 * 60, "Delta", "Beta", 1, "Beta", "pull_in", 3.0));

        LineDetailDto.Brigade first = d.brigades().get(0);
        assertThat(first.name()).isEqualTo("IT-177-01");
        assertThat(first.start()).isEqualTo(6 * 3600);
        assertThat(first.end()).isEqualTo(8 * 3600 + 5 * 60);
        assertThat(first.breaks()).containsExactly(new LineDetailDto.Break(6 * 3600 + 25 * 60, 7 * 3600 + 30 * 60));
        assertThat(first.runs().get(2).kind()).isEqualTo("longer");
        assertThat(d.brigades().get(1).runs().get(2).depot()).isEqualTo("pull_in");
    }

    @Test
    void measuresFullRunsDelaysAndSegmentSpeedsFromPositions() {
        long bus = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-LD1', 'BUS', 'BKM') RETURNING id",
                Long.class);
        long regional = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-LD2', 'BUS', 'OTHER') RETURNING id",
                Long.class);
        // Waits at A, leaves between 07:02 and 07:04, then one stop every 2 minutes; the next run starts at 07:10.
        sample(bus, "07:00", "Delta", 0, 0);
        sample(bus, "07:02", "Delta", 0, 0);
        sample(bus, "07:04", "Delta", 1, 60);
        sample(bus, "07:06", "Delta", 2, 120);
        sample(bus, "07:08", "Delta", 3, 180);
        sample(bus, "07:10", "Alfa", 0, 0);
        sample(bus, "07:12", "Alfa", 1, 4000);      // an outlier, left out of the delay
        sample(regional, "07:05", "Delta", 2, 900); // another operator, left out
        // Later another bus takes over brigade 01 (waiting at the first stop, so no delays or runs from it).
        long second = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-LD3', 'BUS', 'BKM') RETURNING id",
                Long.class);
        for (String time : new String[]{"07:30", "07:32", "07:34", "07:36", "07:38"}) {
            sample(second, time, "Epsilon", 0, 0);
        }

        LineDetailDto.Performance p = lines.performance("IT-177");

        assertThat(p.weekdays()).isEqualTo(1);
        assertThat(p.firstDay()).isEqualTo(DAY);
        assertThat(p.fullRuns()).isEqualTo(1);
        // Departed 07:03 (midpoint), arrived 07:09: 6 minutes over 4 km = 40 km/h.
        assertThat(p.travelTimes()).extracting(LineDetailDto.TravelTimes::label, LineDetailDto.TravelTimes::runs)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Alfa ⇄ Delta", 1),
                        org.assertj.core.groups.Tuple.tuple("Alfa ⇄ Epsilon", 0));
        LineDetailDto.TravelTimes base = p.travelTimes().get(0);
        assertThat(base.durations()).containsExactly(new LineDetailDto.HourStat(7, 1, 6, 6, 6, 6));
        assertThat(base.speeds()).containsExactly(new LineDetailDto.HourStat(7, 1, 40, 40, 40, 40));
        assertThat(base.scheduledShortestMin()).isEqualTo(7);
        assertThat(p.travelTimes().get(1).scheduledShortestMin()).isEqualTo(15);

        assertThat(p.vehicles().vehicles()).extracting(LineDetailDto.VehicleUse::number)
                .containsExactly("IT-LD1", "IT-LD3");
        assertThat(p.vehicles().brigades()).containsExactly(
                new LineDetailDto.BrigadeVehicles("IT-177-01", "IT-LD1", 1, 1, 2));
        assertThat(p.vehicles().switches()).singleElement().satisfies(sw -> {
            assertThat(sw.day()).isEqualTo(DAY);
            assertThat(sw.spells()).extracting(LineDetailDto.VehicleSpell::number).containsExactly("IT-LD1", "IT-LD3");
        });
        assertThat(p.delays()).containsExactly(new LineDetailDto.DelaySlot(420, 120, 3));

        LineDetailDto.SegmentProfile toDelta = p.segments().get(0);
        assertThat(toDelta.headsign()).isEqualTo("Delta");
        assertThat(toDelta.runs()).isEqualTo(1);
        // 1 km, 2 km and 1 km, each in 2 minutes.
        assertThat(toDelta.segments()).extracting(LineDetailDto.Segment::kmh).containsExactly(30.0, 60.0, 30.0);
        assertThat(toDelta.segments().get(1).from()).isEqualTo("Beta");
        assertThat(p.segments().get(1).runs()).isZero();
    }

    @Autowired
    RouteRepository routes;

    @Autowired
    LineTimetable timetables;

    @Autowired
    LineSubtypes subtypes;

    @Autowired
    com.transitanalytics.buslanes.BusLanes busLanes;

    @Autowired
    com.transitanalytics.gtfs.GtfsProperties feeds;

    @Test
    void showsAnotherOperatorsLineOnlySeenOnTheFeed() {
        // Line 700 may be in the timetable (Turośń Kościelna's feed); here it is only on the live feed.
        jdbc.update("DELETE FROM route WHERE gtfs_id = '700'");
        long bus = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-LD7', 'BUS', 'IT-OTHER') RETURNING id",
                Long.class);
        for (String time : new String[]{"07:00", "07:01", "07:02", "07:03", "07:04"}) {
            var at = LocalDateTime.of(DAY, java.time.LocalTime.parse(time)).atZone(ZoneId.of("Europe/Warsaw"));
            jdbc.update("""
                    INSERT INTO position_sample (observed_at, vehicle_id, route_name, brigade, latitude, longitude,
                                                 delay_seconds)
                    VALUES (?, ?, '700', '700-01', 53.1, 23.1, 120)""", at.toOffsetDateTime(), bus);
        }
        // A service of its own, so the feed's lines are read now, not from another test's cache.
        var service = new LineDetailService(jdbc, routes, timetables, subtypes, java.time.Clock.systemUTC(),
                new MapProperties(null, java.util.List.of()), ClassificationConfigs.operators(),
                new DepotRouter(new MapProperties(null, java.util.List.of()), org.springframework.web.client.RestClient.builder(),
                        tools.jackson.databind.json.JsonMapper.builder().build()), busLanes, feeds);

        assertThat(service.lines()).filteredOn(l -> l.line().equals("700")).singleElement().satisfies(l -> {
            assertThat(l.id()).as("not in the timetable").isNull();
            assertThat(l.subtype()).isEqualTo(RouteSubtype.REGIONAL);
        });
        assertThat(service.lines()).extracting(LineDetailDto.LineRef::line).contains("IT-177");

        LineDetailDto.Detail detail = service.detail("700");
        assertThat(detail.serviceId()).isNull();
        assertThat(detail.url()).isNull();
        assertThat(detail.variants()).isEmpty();
        // Where its buses went, from the recordings (the test's bus stood still: a run needs to move).
        assertThat(detail.recorded()).isNotNull();
        assertThat(detail.recorded().variants()).allSatisfy(v -> assertThat(v.path()).isNotEmpty());

        LineDetailDto.Performance p = service.performance("700");
        assertThat(p.fullRuns()).isZero();
        assertThat(p.lastDay()).isEqualTo(DAY);
        assertThat(p.vehicles().vehicles()).extracting(LineDetailDto.VehicleUse::number).contains("IT-LD7");
        assertThat(p.delays()).anySatisfy(d -> assertThat(d.minute()).isEqualTo(420));

        assertThatThrownBy(() -> service.detail("IT-NOWHERE")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.performance("IT-NOWHERE")).isInstanceOf(NotFoundException.class);
    }

    private void sample(long vehicle, String localTime, String headsign, int nextStop, int delay) {
        var at = LocalDateTime.of(DAY, java.time.LocalTime.parse(localTime)).atZone(ZoneId.of("Europe/Warsaw"));
        jdbc.update("""
                INSERT INTO position_sample (observed_at, fetched_at, vehicle_id, route_name, brigade, headsign,
                                             latitude, longitude, delay_seconds, next_stop_sequence)
                VALUES (?, ?, ?, 'IT-177', 'IT-177-01', ?, 53.1, 23.1, ?, ?)""",
                at.toOffsetDateTime(), at.toOffsetDateTime(), vehicle, headsign, delay, nextStop);
    }
}
