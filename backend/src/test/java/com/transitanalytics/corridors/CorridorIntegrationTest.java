package com.transitanalytics.corridors;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.dto.CorridorDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Two routes sharing four stops in a weekday timetable of 2099; the test data is rolled back. */
@DbIntegrationTest
@Transactional
class CorridorIntegrationTest {

    private static final String SERVICE = "IT_CW";

    @Autowired
    CorridorService corridors;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void findsTheStretchTwoRoutesShareInBothDirections() {
        var timetable = new TimetableFixture(jdbc, SERVICE);
        long a = timetable.route("IT-C1", "C1");
        long b = timetable.route("IT-C2", "C2");
        // Shared stops P–Q–R–S (forward), and their opposite platforms back; each route has its own ends.
        String[] names = {"Pe", "Qu", "Ro", "Si"};
        for (int i = 0; i < names.length; i++) {
            timetable.stop("IT-F" + i, names[i] + "(1" + i + ")", null, 53.10 + i * 0.01);
            timetable.stop("IT-B" + i, names[i] + "(2" + i + ")", null, 53.10 + i * 0.01);
        }
        timetable.stop("IT-X", "Xa(30)", null, 53.0);
        timetable.stop("IT-Y", "Ya(31)", null, 53.3);
        // One minute and one km between stops.
        timetable.trip(a, "IT-CT1", 0, null, null, "06:40", 4, "IT-X", "IT-F0", "IT-F1", "IT-F2", "IT-F3");
        timetable.trip(b, "IT-CT2", 0, null, null, "06:50", 4, "IT-F0", "IT-F1", "IT-F2", "IT-F3", "IT-Y");
        timetable.trip(a, "IT-CT3", 1, null, null, "07:20", 4, "IT-B3", "IT-B2", "IT-B1", "IT-B0", "IT-X");
        timetable.trip(b, "IT-CT4", 1, null, null, "07:30", 4, "IT-Y", "IT-B3", "IT-B2", "IT-B1", "IT-B0");

        CorridorDto.Report report = corridors.corridors();

        assertThat(report.serviceId()).isEqualTo(SERVICE);
        assertThat(report.peak()).extracting(p -> p.from() + "–" + p.to())
                .containsExactly("06:30–08:30", "15:00–17:00");
        assertThat(report.corridors()).singleElement().satisfies(c -> {
            assertThat(c.stops()).containsExactly("Pe", "Qu", "Ro", "Si");
            assertThat(c.routes()).containsExactly("C1", "C2");
            assertThat(c.buses()).isEqualTo(4);
            assertThat(c.oneWay()).isFalse();
            assertThat(c.km()).isEqualTo(3.0);
            // 2 departures per direction in 240 peak minutes.
            assertThat(c.peakIntervalMin()).isEqualTo(120.0);
            assertThat(c.peakCv()).isNull();
        });
    }
}
