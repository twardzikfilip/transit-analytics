package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.recording.PositionSampleWriter;
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
 * Monday 5 January 2099: a route along the fixture's shape (the diagonal from 53.1, 23.1 to 53.2, 23.2) with stops
 * IT-TA 1–4, and three trips: one run in full, one with a detour, one whose brigade never comes out; rolled back.
 */
@DbIntegrationTest
@Transactional
class TripAnomalyIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired
    TripAnomalyService anomalies;

    @Autowired
    PositionSampleWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void judgesTheDaysTripsFromTheDatabase() {
        var timetable = new TimetableFixture(jdbc, "IT_TA");
        long route = timetable.route("IT-TA", "IT-TA");
        timetable.shape("IT-TA-S");
        for (int i = 1; i <= 4; i++) {
            double at = 53.10 + (i - 1) * 0.02;
            jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                    "IT-TA" + i, "IT-TA" + i, "IT-TA " + i + " (99" + i + ")", at, 23.10 + (i - 1) * 0.02);
        }
        timetable.trip(route, "ITA-01_IT_TA_1", 0, "IT-TA 4", "IT-TA-S", "07:00", 6, "IT-TA1", "IT-TA2", "IT-TA3",
                "IT-TA4");
        timetable.trip(route, "ITA-01_IT_TA_2", 0, "IT-TA 4", "IT-TA-S", "07:30", 6, "IT-TA1", "IT-TA2", "IT-TA3",
                "IT-TA4");
        timetable.trip(route, "ITA-02_IT_TA_1", 0, "IT-TA 4", "IT-TA-S", "07:00", 6, "IT-TA1", "IT-TA2", "IT-TA3",
                "IT-TA4");

        long bus = vehicle("IT-TA-A");
        // 07:00: heading to stops 1–4 in turn, on the route.
        drive(bus, "ITA-01", "06:59:40", 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4, 4);
        // 07:30: the same, but 500 m off the route while heading to stops 2 and 3 (four reports).
        drive(bus, "ITA-01", "07:29:40", 0, 1, 1, 2, -2, -2, -3, -3, 4, 4, 4, 4);
        // Another bus on the line all morning (no trips of its own), so recording goes on.
        long other = vehicle("IT-TA-B");
        for (Instant t = local("06:50:00"); t.isBefore(local("08:30:00")); t = t.plusSeconds(60)) {
            report(other, "ITA-99", t, 1, 53.10, 23.10);
        }

        TripAnomalyDto.Report report = anomalies.report(TimeWindow.DAY, local("06:00:00"), local("09:00:00"));

        assertThat(report.summary()).isEqualTo(new TripAnomalyDto.Summary(3, 3, 1, 1, 0, 0, 0, 0, 1, 0, 0));
        assertThat(report.tripsTotal()).isEqualTo(2);
        assertThat(report.trips()).extracting(TripAnomalyDto.Trip::kind).containsExactly("detour", "cancelled");
        TripAnomalyDto.Trip detour = report.trips().get(0);
        assertThat(detour.tripId()).isEqualTo("ITA-01_IT_TA_2");
        assertThat(detour.brigade()).isEqualTo("ITA-01");
        assertThat(detour.vehicles()).containsExactly("IT-TA-A");
        assertThat(detour.firstStop()).as("without the stop code").isEqualTo("IT-TA 1");
        assertThat(detour.lastStop()).isEqualTo("IT-TA 4");
        assertThat(detour.scheduledDeparture()).isEqualTo(local("07:30:00"));
        assertThat(detour.stops()).isEqualTo(4);
        // 500 m east of a route heading 59° from east (in metres): 500 × sin 59° ≈ 430 m from it.
        assertThat(detour.detourMeters()).isBetween(420, 440);
        assertThat(detour.startDelay()).isZero();
        TripAnomalyDto.Trip cancelled = report.trips().get(1);
        assertThat(cancelled.brigade()).isEqualTo("ITA-02");
        assertThat(cancelled.note()).isEqualTo("No bus ran brigade ITA-02 all day while recording.");
        assertThat(report.lines()).singleElement().satisfies(l -> {
            assertThat(l.line()).isEqualTo("IT-TA");
            assertThat(l.judged()).isEqualTo(3);
        });

        // Only the trips leaving in the window count.
        assertThat(anomalies.report(TimeWindow.HOUR, local("07:20:00"), local("09:00:00")).summary().scheduled())
                .isEqualTo(1);
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    private static Instant local(String hhmmss) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(LocalTime.parse(hhmmss).toSecondOfDay());
    }

    /**
     * Reports every 20 s from {@code start}: the next stop's sequence number in turn; a negative one is that stop,
     * but 500 m east of the route.
     */
    private void drive(long vehicle, String brigade, String start, int... nextStops) {
        Instant t = local(start);
        for (int next : nextStops) {
            int seq = Math.abs(next);
            double lat = 53.10 + Math.max(0, seq - 2) * 0.02 + 0.01;
            report(vehicle, brigade, t, seq, lat, 23.10 + (lat - 53.10) + (next < 0 ? 0.0075 : 0));
            t = t.plusSeconds(20);
        }
    }

    private void report(long vehicle, String brigade, Instant t, int nextStop, double lat, double lon) {
        writer.write(List.of(new LivePositionDto(vehicle, "x", "BUS", "BKM", null, "IT-TA", null, brigade, "IT-TA 4",
                lat, lon, 0.0, 0L, t, List.of(new LivePositionDto.UpcomingStop(nextStop, "IT-TA " + nextStop, null,
                null)), null)), t);
    }
}
