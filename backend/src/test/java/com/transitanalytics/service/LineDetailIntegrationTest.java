package com.transitanalytics.service;

import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.LineDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** A small line in a weekday timetable of 2099, with recorded positions; the test data is rolled back. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false",
        "app.recording.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class LineDetailIntegrationTest {

    private static final String SERVICE = "IT_W";
    /** A Monday. */
    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired
    LineDetailService lines;

    @Autowired
    JdbcTemplate jdbc;

    long route;

    @BeforeEach
    void timetable() {
        // The test service runs on every weekday of 2099, so it is the weekday timetable.
        jdbc.update("""
                INSERT INTO calendar_date (service_id, date, exception_type)
                SELECT ?, d::date, 1 FROM generate_series(date '2099-01-01', date '2099-12-31', interval '1 day') d
                WHERE extract(isodow FROM d) <= 5""", SERVICE);
        route = jdbc.queryForObject("""
                INSERT INTO route (gtfs_id, long_name, route_type, subtype) VALUES ('IT-177', '177', 3, 'suburban')
                RETURNING id""", Long.class);
        // A (zone 1, km 0) – B (1, km 1) – C (2, km 3) – D (2, km 4); the longer variant goes on to E (2, km 6).
        stop("IT-A", "Alfa(901)", "1");
        stop("IT-B", "Beta(902)", "1");
        stop("IT-C", "Gamma(903)", "2");
        stop("IT-D", "Delta(904)", "2");
        stop("IT-E", "Epsilon(905)", "2");
        trip("IT-177-01_1", "IT-S0", 0, "Delta", "06:00", 10, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        trip("IT-177-01_2", "IT-S1", 1, "Alfa", "06:15", 10, "IT-D:0", "IT-C:1", "IT-B:3", "IT-A:4");
        trip("IT-177-01_3", "IT-SL", 0, "Epsilon", "07:30", 15, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4", "IT-E:6");
        trip("IT-177-02_1", "IT-S0", 0, "Delta", "06:05", 7, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        trip("IT-177-02_2", "IT-S0", 0, "Delta", "07:00", 10, "IT-A:0", "IT-B:1", "IT-C:3", "IT-D:4");
        for (String shape : new String[]{"IT-S0", "IT-S1", "IT-SL"}) {
            jdbc.update("INSERT INTO shape_point (shape_id, sequence, latitude, longitude) VALUES (?, 1, 53.1, 23.1), "
                    + "(?, 2, 53.2, 23.2)", shape, shape);
        }
    }

    @Test
    void describesVariantsZoneTwoAndBrigadesFromTheTimetable() {
        LineDetailDto.Detail d = lines.detail("IT-177");

        assertThat(d.subtype()).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(d.serviceId()).isEqualTo(SERVICE);
        assertThat(d.variants()).extracting(LineDetailDto.Variant::headsign, LineDetailDto.Variant::kind,
                        LineDetailDto.Variant::km, LineDetailDto.Variant::tripsPerDay)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Delta", "main", 4.0, 3),
                        org.assertj.core.groups.Tuple.tuple("Epsilon", "longer", 6.0, 1),
                        org.assertj.core.groups.Tuple.tuple("Alfa", "main", 4.0, 1));
        LineDetailDto.Variant main = d.variants().get(0);
        assertThat(main.firstStop()).isEqualTo("Alfa");
        assertThat(main.points()).hasSize(2);
        assertThat(main.shortestMin()).isEqualTo(7);

        assertThat(d.base()).isEqualTo(new LineDetailDto.Base(4.0, 7, 10, 24.0, 34.3));
        // B (zone 1) → C (zone 2) counts half: 1 km, plus C → D: 1 km.
        assertThat(d.zone2()).extracting(LineDetailDto.Zone2::km, LineDetailDto.Zone2::stops)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2.0, 2),
                        org.assertj.core.groups.Tuple.tuple(2.0, 2));

        LineDetailDto.Brigade first = d.brigades().get(0);
        assertThat(first.name()).isEqualTo("IT-177-01");
        assertThat(first.start()).isEqualTo(6 * 3600);
        assertThat(first.end()).isEqualTo(7 * 3600 + 45 * 60);
        assertThat(first.breaks()).containsExactly(new LineDetailDto.Break(6 * 3600 + 25 * 60, 7 * 3600 + 30 * 60));
        assertThat(first.runs().get(2).kind()).isEqualTo("longer");
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

        LineDetailDto.Performance p = lines.performance("IT-177");

        assertThat(p.weekdays()).isEqualTo(1);
        assertThat(p.firstDay()).isEqualTo(DAY);
        assertThat(p.fullRuns()).isEqualTo(1);
        // Departed 07:03 (midpoint), arrived 07:09: 6 minutes over 4 km = 40 km/h.
        assertThat(p.durations()).containsExactly(new LineDetailDto.HourStat(7, 1, 6, 6, 6, 6));
        assertThat(p.speeds()).containsExactly(new LineDetailDto.HourStat(7, 1, 40, 40, 40, 40));
        assertThat(p.delays()).containsExactly(new LineDetailDto.DelaySlot(420, 120, 3));

        LineDetailDto.SegmentProfile toDelta = p.segments().get(0);
        assertThat(toDelta.headsign()).isEqualTo("Delta");
        assertThat(toDelta.runs()).isEqualTo(1);
        // 1 km, 2 km and 1 km, each in 2 minutes.
        assertThat(toDelta.segments()).extracting(LineDetailDto.Segment::kmh).containsExactly(30.0, 60.0, 30.0);
        assertThat(toDelta.segments().get(1).from()).isEqualTo("Beta");
        assertThat(p.segments().get(1).runs()).isZero();
    }

    private void stop(String gtfsId, String name, String zone) {
        jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude, zone_id) VALUES (?, ?, ?, 53.1, 23.1, ?)",
                gtfsId, gtfsId, name, zone);
    }

    /** @param stops "stop gtfs id:km", one minute apart except the last, which arrives at start + minutes */
    private void trip(String gtfsId, String shape, int direction, String headsign, String start, int minutes,
                      String... stops) {
        long trip = jdbc.queryForObject("""
                INSERT INTO trip (gtfs_id, route_id, service_id, direction, headsign, shape_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, gtfsId, route, SERVICE, direction, headsign,
                shape);
        int startMinute = Integer.parseInt(start.substring(0, 2)) * 60 + Integer.parseInt(start.substring(3));
        for (int i = 0; i < stops.length; i++) {
            String[] parts = stops[i].split(":");
            int m = startMinute + (i == stops.length - 1 ? minutes : i);
            String time = "%02d:%02d:00".formatted(m / 60, m % 60);
            jdbc.update("""
                    INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time,
                                           shape_dist_traveled)
                    VALUES (?, (SELECT id FROM stop WHERE gtfs_id = ?), ?, ?, ?, ?)""",
                    trip, parts[0], i + 1, time, time, Double.parseDouble(parts[1]));
        }
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
