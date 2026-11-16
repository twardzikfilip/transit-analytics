package com.transitanalytics.service;

import com.transitanalytics.dto.CorridorDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Two lines sharing four stops in a weekday timetable of 2099; the test data is rolled back. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.enabled=false",
        "app.recording.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class CorridorIntegrationTest {

    private static final String SERVICE = "IT_CW";

    @Autowired
    CorridorService corridors;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void findsTheStretchTwoLinesShareInBothDirections() {
        jdbc.update("""
                INSERT INTO calendar_date (service_id, date, exception_type)
                SELECT ?, d::date, 1 FROM generate_series(date '2099-01-01', date '2099-12-31', interval '1 day') d
                WHERE extract(isodow FROM d) <= 5""", SERVICE);
        long a = route("IT-C1", "C1");
        long b = route("IT-C2", "C2");
        // Shared stops P–Q–R–S (forward), and their opposite platforms back; each line has its own ends.
        String[] names = {"Pe", "Qu", "Ro", "Si"};
        for (int i = 0; i < names.length; i++) {
            stop("IT-F" + i, names[i] + "(1" + i + ")", 53.10 + i * 0.01);
            stop("IT-B" + i, names[i] + "(2" + i + ")", 53.10 + i * 0.01);
        }
        stop("IT-X", "Xa(30)", 53.0);
        stop("IT-Y", "Ya(31)", 53.3);
        trip(a, "IT-CT1", 0, "06:40", "IT-X", "IT-F0", "IT-F1", "IT-F2", "IT-F3");
        trip(b, "IT-CT2", 0, "06:50", "IT-F0", "IT-F1", "IT-F2", "IT-F3", "IT-Y");
        trip(a, "IT-CT3", 1, "07:20", "IT-B3", "IT-B2", "IT-B1", "IT-B0", "IT-X");
        trip(b, "IT-CT4", 1, "07:30", "IT-Y", "IT-B3", "IT-B2", "IT-B1", "IT-B0");

        CorridorDto.Report report = corridors.corridors();

        assertThat(report.serviceId()).isEqualTo(SERVICE);
        assertThat(report.peak()).extracting(p -> p.from() + "–" + p.to()).containsExactly("06:30–08:30", "15:00–17:00");
        assertThat(report.corridors()).singleElement().satisfies(c -> {
            assertThat(c.stops()).containsExactly("Pe", "Qu", "Ro", "Si");
            assertThat(c.lines()).containsExactly("C1", "C2");
            assertThat(c.buses()).isEqualTo(4);
            assertThat(c.oneWay()).isFalse();
            assertThat(c.km()).isEqualTo(3.0);
            // 2 departures per direction in 240 peak minutes.
            assertThat(c.peakIntervalMin()).isEqualTo(120.0);
            assertThat(c.peakCv()).isNull();
        });
    }

    private long route(String gtfsId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO route (gtfs_id, long_name, route_type) VALUES (?, ?, 3) RETURNING id", Long.class,
                gtfsId, name);
    }

    private void stop(String gtfsId, String name, double latitude) {
        jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, 23.1)",
                gtfsId, gtfsId, name, latitude);
    }

    /** One minute and one km between stops. */
    private void trip(long route, String gtfsId, int direction, String start, String... stops) {
        long trip = jdbc.queryForObject("""
                INSERT INTO trip (gtfs_id, route_id, service_id, direction) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, gtfsId, route, SERVICE, direction);
        int minute = Integer.parseInt(start.substring(0, 2)) * 60 + Integer.parseInt(start.substring(3));
        for (int i = 0; i < stops.length; i++) {
            String time = "%02d:%02d:00".formatted((minute + i) / 60, (minute + i) % 60);
            jdbc.update("""
                    INSERT INTO stop_time (trip_id, stop_id, stop_sequence, arrival_time, departure_time,
                                           shape_dist_traveled)
                    VALUES (?, (SELECT id FROM stop WHERE gtfs_id = ?), ?, ?, ?, ?)""",
                    trip, stops[i], i + 1, time, time, (double) i);
        }
    }
}
