package com.transitanalytics.dwell;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.common.ReportWindow;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.dto.PositionHistoryDto;
import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.zbiorkom.FeedPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buses recorded on 6 January 2099 along a route running north at longitude 23.5, with stops at 53.50 (terminal),
 * 53.52 and 53.54 (terminal); rolled back.
 */
@DbIntegrationTest
@Transactional
class StopDwellIntegrationTest {

    private static final double LON = 23.5;

    @Autowired
    StopDwellService dwell;

    @Autowired
    Timetables timetables;

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void measuresTheHaltsAtTheStopsAlongTheRoute() {
        var timetable = new TimetableFixture(jdbc, "IT_DW");
        long route = timetable.route("IT-D1", "IT-D1");
        stop("IT-DW1", "Południe(901)", 53.50);
        stop("IT-DW2", "Środek(902)", 53.52);
        stop("IT-DW3", "Północ(903)", 53.54);
        timetable.trip(route, "IT-D1-01_IT_DW_1", 0, "Północ", null, "06:00", 20, "IT-DW1", "IT-DW2", "IT-DW3");

        // The ends, where buses lay over, aren't along the route.
        assertThat(timetables.stopsAlongRoutes().get("IT-D1")).extracting(Timetables.Stop::name)
                .containsExactly("Środek");

        long bus = vehicle("IT-DA");
        for (int run = 0; run < 20; run++) {
            // In at 30 km/h, two still steps (40 s) at the middle stop, out again.
            drive(bus, "IT-D1", LocalTime.of(7, 0).plusMinutes(5L * run), 53.5185, 53.52, 53.52, 53.52, 53.5215);
        }

        List<PositionHistoryDto.StopDwell> top = dwell.top(new ReportWindow(Instant.parse("2099-01-06T04:00:00Z"),
                Instant.parse("2099-01-06T12:00:00Z")), null, null);

        assertThat(top).containsExactly(new PositionHistoryDto.StopDwell("Środek", 40, 20));
    }

    private void stop(String gtfsId, String name, double latitude) {
        jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                gtfsId, gtfsId, name, latitude, LON);
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    /** A report every 20 s from {@code start} (local time), at each latitude in turn. */
    private void drive(long vehicle, String route, LocalTime start, double... latitudes) {
        Instant t = ServiceDay.gtfsTimeZero(LocalDate.of(2099, 1, 6)).plusSeconds(start.toSecondOfDay());
        for (double lat : latitudes) {
            writer.write(List.of(new FeedPosition(vehicle, "x", "BUS", "BKM", null, route, null, route + "-01",
                    "Test", lat, LON, 0.0, 0L, t, List.of())), t);
            t = t.plusSeconds(20);
        }
    }
}
