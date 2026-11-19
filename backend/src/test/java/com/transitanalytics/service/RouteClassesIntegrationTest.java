package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.classification.RouteClasses;
import com.transitanalytics.domain.RouteCapacity;
import com.transitanalytics.domain.RouteGeometry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DbIntegrationTest
@Transactional
class RouteClassesIntegrationTest {

    private static final String BKM = "Zarząd Białostockiej Komunikacji Miejskiej";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RouteClasses routeClasses;

    @Autowired
    LineDetailService lineDetail;

    @Test
    void classesTheOperatorsLinesFromTheirShapeTimetableAndBuses() {
        TimetableFixture fixture = new TimetableFixture(jdbc, "IT-RC");
        long agency = jdbc.queryForObject("""
                INSERT INTO agency (gtfs_id, name, url, timezone) VALUES ('IT-RC', ?, 'https://example.org',
                'Europe/Warsaw') RETURNING id""", Long.class, BKM);
        // From Rynek Kościuszki out north, every 10 minutes all day, both ways; named 103, so run by KZK.
        long radial = fixture.route("IT-RC-1", "103");
        long other = fixture.route("IT-RC-2", "IT-OTHER");
        // A night line along the same route: a geometry, but no capacity class.
        long night = fixture.route("IT-RC-3", "IT-N", "night_urban");
        jdbc.update("UPDATE route SET agency_id = ? WHERE id IN (?, ?)", agency, radial, night);
        jdbc.update("""
                INSERT INTO shape_point (shape_id, sequence, latitude, longitude)
                VALUES ('IT-RC-S', 1, 53.1322, 23.1590), ('IT-RC-S', 2, 53.15, 23.16), ('IT-RC-S', 3, 53.17, 23.16)""");
        fixture.stop("IT-RC-A", "Rynek", null, 53.1322);
        fixture.stop("IT-RC-B", "Pętla", null, 53.17);
        for (int m = 5 * 60, i = 0; m <= 22 * 60; m += 10, i++) {
            String start = "%02d:%02d".formatted(m / 60, m % 60);
            fixture.trip(radial, "IT-RC-1-" + i, 0, "Pętla", "IT-RC-S", start, 20, "IT-RC-A", "IT-RC-B");
            fixture.trip(radial, "IT-RC-1-" + i + "b", 1, "Rynek", "IT-RC-S", start, 20, "IT-RC-B", "IT-RC-A");
        }
        fixture.trip(other, "IT-RC-2-1", 0, "X", "IT-RC-S", "07:00", 20, "IT-RC-A", "IT-RC-B");
        fixture.trip(night, "IT-RC-3-1", 0, "Pętla", "IT-RC-S", "07:00", 20, "IT-RC-A", "IT-RC-B");

        // Its buses over the last week: an articulated one, every day.
        long bus = jdbc.queryForObject("""
                INSERT INTO vehicle (vehicle_number, type, agency, model, vehicle_class)
                VALUES ('IT-RC-V', 'BUS', 'BKM', 'Solaris Urbino 18', 'mega') RETURNING id""", Long.class);
        jdbc.update("""
                INSERT INTO position_sample (observed_at, vehicle_id, route_name, latitude, longitude)
                SELECT now() - d * interval '1 day', ?, 'IT-RC-1', 53.15, 23.16 FROM generate_series(1, 7) d""", bus);

        var classified = routeClasses.classifyAll();

        assertThat(classified).filteredOn(c -> c.routeId() == radial).singleElement().satisfies(c -> {
            assertThat(c.geometry()).isEqualTo(RouteGeometry.RADIAL);
            assertThat(c.capacity()).isEqualTo(RouteCapacity.MAIN);
            assertThat(c.inputs().peakHeadway()).isEqualTo(10.0);
            assertThat(c.inputs().megaWeekday()).isEqualTo(1.0);
            assertThat(c.operatingAgency()).isEqualTo("KZK");
        });
        assertThat(classified).noneMatch(c -> c.routeId() == other);
        assertThat(classified).filteredOn(c -> c.routeId() == night).singleElement().satisfies(c -> {
            assertThat(c.geometry()).isEqualTo(RouteGeometry.RADIAL);
            assertThat(c.capacity()).isNull();
        });
        assertThat(jdbc.queryForMap("SELECT geometry, capacity_class, operating_agency FROM route WHERE id = ?",
                radial)).isEqualTo(Map.of("geometry", "radial", "capacity_class", "main", "operating_agency", "KZK"));
        Map<String, Object> unclassified = jdbc.queryForMap("SELECT geometry, capacity_class FROM route WHERE id = ?",
                other);
        assertThat(unclassified.get("geometry")).isNull();
        assertThat(unclassified.get("capacity_class")).isNull();

        // The Routes page sees them.
        assertThat(lineDetail.lines()).filteredOn(l -> "IT-RC-1".equals(l.line())).singleElement()
                .satisfies(l -> {
                    assertThat(l.geometry()).isEqualTo(RouteGeometry.RADIAL);
                    assertThat(l.capacityClass()).isEqualTo(RouteCapacity.MAIN);
                    assertThat(l.operatingAgency()).isEqualTo("KZK");
                });
        var detail = lineDetail.detail("IT-RC-1");
        assertThat(detail.geometry()).isEqualTo(RouteGeometry.RADIAL);
        // Run by KZK from its depot: its buses come from there, whichever depot is nearer.
        assertThat(detail.operator().code()).isEqualTo("KZK");
        assertThat(detail.operator().depot()).isEqualTo("Zajezdnia autobusowa KZK");
        assertThat(detail.operator().organiser()).startsWith("BKM");
        assertThat(detail.depotLinks()).isNotEmpty().allSatisfy(l -> assertThat(l.depot()).isEqualTo("KZK"));

        // Every night again, the same way.
        jdbc.update("UPDATE route SET geometry = NULL WHERE id = ?", radial);
        routeClasses.nightly();
        assertThat(jdbc.queryForObject("SELECT geometry FROM route WHERE id = ?", String.class, radial))
                .isEqualTo("radial");
    }
}
