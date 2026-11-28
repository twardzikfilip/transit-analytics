package com.transitanalytics.brigades;

import com.transitanalytics.data.Reports;
import com.transitanalytics.data.Shapes;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.TimetableFixture;
import com.transitanalytics.common.DaySummaries;
import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.dto.TripRunDto;
import com.transitanalytics.gtfs.GtfsProperties;
import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.trips.TripAnomalyService;
import com.transitanalytics.trips.TripRunService;
import com.transitanalytics.zbiorkom.FeedPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Another operator's feed "it" (buses ITX on the live feed), its timetable without brigades: routes IT-R1 and IT-R2,
 * recorded on Monday 5 January 2099. Bus A runs IT-R1 at 07:00 and IT-R2 at 07:30 as "1"; bus B runs IT-R1 at 08:00 as
 * "2"; IT-R2's 09:00 trip doesn't run. Rolled back.
 */
@DbIntegrationTest
@Transactional
class RecordedBrigadesIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2099, 1, 5);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    PositionSampleWriter writer;

    @Test
    void learnsTheBrigadesFromTheRecordingsAndShowsTheirDays() {
        var timetable = new TimetableFixture(jdbc, "IT_RB");
        jdbc.update("UPDATE calendar_date SET feed = 'it' WHERE service_id = 'IT_RB'");
        long agency = jdbc.queryForObject("""
                INSERT INTO agency (feed, gtfs_id, name, url, timezone)
                VALUES ('it', 'ITA', 'Other operator', 'https://example.org', 'Europe/Warsaw') RETURNING id""",
                Long.class);
        timetable.shape("IT-R-S");
        for (int i = 1; i <= 4; i++) {
            double at = 53.10 + (i - 1) * 0.02;
            jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                    "IT-R" + i, "IT-R" + i, "IT-R " + i, at, 23.10 + (i - 1) * 0.02);
        }
        String[] stops = {"IT-R1", "IT-R2", "IT-R3", "IT-R4"};
        long r1 = timetable.route("IT-R1", "IT-R1");
        long r2 = timetable.route("IT-R2", "IT-R2");
        jdbc.update("UPDATE route SET feed = 'it', agency_id = ? WHERE id IN (?, ?)", agency, r1, r2);
        timetable.trip(r1, "101", 0, "IT-R 4", "IT-R-S", "07:00", 6, stops);
        timetable.trip(r2, "201", 0, "IT-R 4", "IT-R-S", "07:30", 6, stops);
        timetable.trip(r1, "102", 0, "IT-R 4", "IT-R-S", "08:00", 6, stops);
        timetable.trip(r2, "202", 0, "IT-R 4", "IT-R-S", "09:00", 6, stops);
        jdbc.update("UPDATE trip SET feed = 'it' WHERE route_id IN (?, ?)", r1, r2);

        long a = vehicle("IT-R-A");
        long b = vehicle("IT-R-B");
        drive(a, "IT-R1", "1", "06:59:40");
        drive(a, "IT-R2", "1", "07:29:40");
        drive(b, "IT-R1", "2", "07:59:40");
        // Recording goes on all morning (a city bus: not one learnt from).
        long other = jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES ('IT-R-C', 'BUS', 'BKM') RETURNING id",
                Long.class);
        for (Instant t = local("06:50:00"); t.isBefore(local("10:00:00")); t = t.plusSeconds(60)) {
            report(other, "IT-R1", "9", t, 1);
        }

        Clock tuesday = Clock.fixed(local("09:00:00").plusSeconds(86_400), ZoneOffset.UTC);
        var feeds = new GtfsProperties(null, null, null, new GtfsProperties.Feed(null, "City", null),
                Map.of("it", new GtfsProperties.Feed(null, "Test feed", "ITX")), null);
        var timetables = new Timetables(jdbc, feeds);
        var reports = new Reports(jdbc, tuesday);
        var shapes = new Shapes(jdbc);
        var anomalies = new TripAnomalyService(timetables, reports, shapes, tuesday, DaySummaries.none());
        new RecordedBrigades(jdbc, transaction, timetables, reports, anomalies, feeds, tuesday).learn("it");

        assertThat(jdbc.queryForList("""
                SELECT line || ':' || live_brigade || '=' || brigade FROM recorded_brigade_line
                WHERE agency = 'ITX' ORDER BY 1""", String.class))
                .containsExactly("IT-R1:1=IT-R1-1", "IT-R1:2=IT-R1-2", "IT-R2:1=IT-R1-1");
        assertThat(jdbc.queryForList("""
                SELECT t.gtfs_id || '=' || b.brigade FROM trip_brigade b JOIN trip t ON t.id = b.trip_id
                WHERE t.feed = 'it' ORDER BY 1""", String.class))
                .as("the 09:00 trip never ran").containsExactly("101=IT-R1-1", "102=IT-R1-2", "201=IT-R1-1");

        var service = new BrigadeDayService(jdbc);
        var runs = new TripRunService(timetables, reports, shapes, anomalies, tuesday);
        assertThat(service.brigades("it").brigades()).extracting(BrigadeDto.BrigadeRef::brigade)
                .containsExactly("IT-R1-1", "IT-R1-2");
        assertThat(service.brigades("it").brigades().get(0).routes()).containsExactly("IT-R1", "IT-R2");

        TripRunDto.Day day = runs.day(MONDAY, "IT-R1-1");
        List<BrigadeDto.Trip> trips = day.trips().stream().map(TripRunDto.Trip::trip).toList();
        assertThat(trips).extracting(BrigadeDto.Trip::route).containsExactly("IT-R1", "IT-R2");
        assertThat(trips).extracting(BrigadeDto.Trip::kind).containsExactly("completed", "completed");
        assertThat(trips).allSatisfy(trip -> assertThat(trip.runs()).extracting(BrigadeDto.ActualRun::bus)
                .containsExactly("IT-R-A"));
        assertThat(day.summary().buses()).containsExactly("IT-R-A");
        assertThat(runs.day(MONDAY, "IT-R1-2").trips()).extracting(TripRunDto.Trip::trip).singleElement()
                .satisfies(trip -> assertThat(trip.runs()).extracting(BrigadeDto.ActualRun::bus)
                        .containsExactly("IT-R-B"));
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'ITX') RETURNING id",
                Long.class, number);
    }

    private static Instant local(String hhmmss) {
        return ServiceDay.gtfsTimeZero(MONDAY).plusSeconds(LocalTime.parse(hhmmss).toSecondOfDay());
    }

    /** A run on time: reports every 20 s from {@code start}, heading to each stop in turn. */
    private void drive(long vehicle, String route, String brigade, String start) {
        Instant t = local(start);
        for (int next : new int[] {1, 1, 2, 2, 3, 3, 4, 4, 4, 4}) {
            report(vehicle, route, brigade, t, next);
            t = t.plusSeconds(20);
        }
    }

    private void report(long vehicle, String route, String brigade, Instant t, int nextStop) {
        double lat = 53.10 + Math.max(0, nextStop - 2) * 0.02 + 0.01;
        writer.write(List.of(new FeedPosition(vehicle, "x", "BUS", "ITX", null, route, null, brigade, "IT-R 4",
                lat, 23.10 + (lat - 53.10), 0.0, 0L, t, List.of(new FeedPosition.UpcomingStop(nextStop,
                route + " " + nextStop, null, null)))), t);
    }
}
