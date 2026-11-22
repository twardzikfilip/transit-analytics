package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.BrigadeDto;
import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.zbiorkom.FeedPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monday 5 January 2099, 09:00: brigade ITB-01 of line IT-BD (agency IT-BDA) with three trips: run in full by bus A,
 * lost by bus A at its second stop, and run by bus B; brigade ITB-02 never comes out. Rolled back.
 */
@DbIntegrationTest
@Transactional
class BrigadeDayIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TripAnomalyService anomalies;

    @Autowired
    PositionSampleWriter writer;

    @Test
    void showsEachTripScheduledAndWhatRan() {
        var timetable = new TimetableFixture(jdbc, "IT_BD");
        long route = timetable.route("IT-BD", "IT-BD");
        long agency = jdbc.queryForObject("""
                INSERT INTO agency (gtfs_id, name, url, timezone)
                VALUES ('IT-BDA', 'Test agency', 'https://example.org', 'Europe/Warsaw') RETURNING id""", Long.class);
        jdbc.update("UPDATE route SET agency_id = ? WHERE id = ?", agency, route);
        timetable.shape("IT-BD-S");
        for (int i = 1; i <= 4; i++) {
            double at = 53.10 + (i - 1) * 0.02;
            jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                    "IT-BD" + i, "IT-BD" + i, "IT-BD " + i, at, 23.10 + (i - 1) * 0.02);
        }
        String[] stops = {"IT-BD1", "IT-BD2", "IT-BD3", "IT-BD4"};
        timetable.trip(route, "ITB-01_IT_BD_1", 0, "IT-BD 4", "IT-BD-S", "07:00", 6, stops);
        timetable.trip(route, "ITB-01_IT_BD_2", 0, "IT-BD 4", "IT-BD-S", "07:30", 6, stops);
        timetable.trip(route, "ITB-01_IT_BD_3", 0, "IT-BD 4", "IT-BD-S", "08:00", 6, stops);
        timetable.trip(route, "ITB-02_IT_BD_1", 0, "IT-BD 4", "IT-BD-S", "07:00", 6, stops);
        // Another operator's feed, whose timetable doesn't name brigades.
        long otherRoute = timetable.route("IT-BX", "IT-BX");
        long otherAgency = jdbc.queryForObject("""
                INSERT INTO agency (feed, gtfs_id, name, url, timezone)
                VALUES ('it', 'IT-BXA', 'Other operator', 'https://example.org', 'Europe/Warsaw') RETURNING id""",
                Long.class);
        jdbc.update("UPDATE route SET feed = 'it', agency_id = ? WHERE id = ?", otherAgency, otherRoute);
        long otherTrip = timetable.trip(otherRoute, "IT-BX-1", 0, "IT-BD 4", "IT-BD-S", "07:00", 6, stops);
        jdbc.update("UPDATE trip SET feed = 'it' WHERE id = ?", otherTrip);

        long a = vehicle("IT-BD-A");
        long b = vehicle("IT-BD-B");
        drive(a, "ITB-01", "06:59:40", 60, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4);
        // Heading to stop 2, then gone (recording goes on: another bus reports all morning).
        drive(a, "ITB-01", "07:29:40", 30, 1, 1, 2, 2);
        drive(b, "ITB-01", "07:59:40", 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4);
        long other = vehicle("IT-BD-C");
        for (Instant t = local("06:50:00"); t.isBefore(local("08:59:00")); t = t.plusSeconds(60)) {
            report(other, "ITB-99", t, 0, 1);
        }

        var service = new BrigadeDayService(jdbc, anomalies, Clock.fixed(local("09:00:00"), ZoneOffset.UTC));

        BrigadeDto.BrigadeList list = service.brigades("IT-BDA");
        assertThat(list.agency()).isEqualTo("IT-BDA");
        assertThat(list.agencies()).extracting(BrigadeDto.Agency::id).contains("IT-BDA");
        assertThat(list.brigades()).extracting(BrigadeDto.BrigadeRef::brigade).containsExactly("ITB-01", "ITB-02");
        assertThat(list.brigades().get(0).lines()).containsExactly("IT-BD");
        assertThat(service.brigades("IT-NONE").agency()).as("unknown: the main agency").isNotEqualTo("IT-NONE");
        BrigadeDto.BrigadeList unnamed = service.brigades("it-IT-BXA");
        assertThat(unnamed.agencies()).as("every agency is offered").extracting(BrigadeDto.Agency::id)
                .contains("IT-BDA", "it-IT-BXA");
        assertThat(unnamed.agency()).isEqualTo("it-IT-BXA");
        assertThat(unnamed.brigades()).as("its timetable names no brigades").isEmpty();

        BrigadeDto.Day day = service.day("ITB-01", DAY);
        assertThat(day.trips()).extracting(BrigadeDto.Trip::kind).containsExactly("completed", "lost", "completed");
        BrigadeDto.Trip first = day.trips().get(0);
        assertThat(first.from()).isEqualTo("IT-BD 1");
        assertThat(first.to()).isEqualTo("IT-BD 4");
        assertThat(first.runs()).singleElement().satisfies(r -> {
            assertThat(r.bus()).isEqualTo("IT-BD-A");
            assertThat(r.fromStop()).isEqualTo(1);
            assertThat(r.toStop()).isEqualTo(4);
            assertThat(r.startDelay()).isEqualTo(60);
            assertThat(r.departure()).isEqualTo(local("07:01:00"));
        });
        BrigadeDto.Trip lost = day.trips().get(1);
        assertThat(EnglishPhrases.text(lost.note())).contains("stopped reporting");
        assertThat(lost.missing()).singleElement().satisfies(m -> {
            assertThat(m.fromStop()).isEqualTo(2);
            assertThat(m.toStop()).isEqualTo(4);
            assertThat(m.why()).isEqualTo("not-run");
        });
        assertThat(day.trips().get(2).runs()).extracting(BrigadeDto.ActualRun::bus).containsExactly("IT-BD-B");
        assertThat(day.summary().buses()).containsExactly("IT-BD-A", "IT-BD-B");
        assertThat(day.summary().judged()).isEqualTo(3);
        assertThat(day.summary().abnormal()).isEqualTo(1);
        assertThat(day.summary().onTime()).isEqualTo(3);

        assertThat(service.day("ITB-77", DAY).trips()).as("no such brigade").isEmpty();
        assertThat(service.today()).isEqualTo(DAY);
    }

    /**
     * Night line IT-BN on the weekday timetable: its trips after midnight are given as 00:10 and 02:58 on the next
     * date (as the city's night lines are), so on Tuesday's timetable they run the night after Monday, Monday's service
     * day. The second one ends at 03:04, after Tuesday's service day starts.
     */
    @Test
    void countsNightTripsOnTheNextDatesTimetableAsTheEveningBefore() {
        var timetable = new TimetableFixture(jdbc, "IT_BN");
        long route = timetable.route("IT-BN", "IT-BN", "night_urban");
        long agency = jdbc.queryForObject("""
                INSERT INTO agency (gtfs_id, name, url, timezone)
                VALUES ('IT-BNA', 'Night agency', 'https://example.org', 'Europe/Warsaw') RETURNING id""", Long.class);
        jdbc.update("UPDATE route SET agency_id = ? WHERE id = ?", agency, route);
        timetable.shape("IT-BN-S");
        for (int i = 1; i <= 4; i++) {
            double at = 53.10 + (i - 1) * 0.02;
            jdbc.update("INSERT INTO stop (gtfs_id, code, name, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                    "IT-BN" + i, "IT-BN" + i, "IT-BN " + i, at, 23.10 + (i - 1) * 0.02);
        }
        String[] stops = {"IT-BN1", "IT-BN2", "IT-BN3", "IT-BN4"};
        timetable.trip(route, "ITN-01_IT_BN_1", 0, "IT-BN 4", "IT-BN-S", "00:10", 6, stops);
        timetable.trip(route, "ITN-01_IT_BN_2", 0, "IT-BN 4", "IT-BN-S", "02:58", 6, stops);

        Instant tuesday = ServiceDay.gtfsTimeZero(DAY.plusDays(1));
        long bus = vehicle("IT-BN-A");
        drive(bus, "IT-BN", "ITN-01", tuesday.plusSeconds(9 * 60 + 40), 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4);
        // From 03:00 on, the reports are after Tuesday's service day starts; they still count for Monday's trip.
        drive(bus, "IT-BN", "ITN-01", tuesday.plusSeconds(2 * 3600 + 59 * 60 + 40), 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4);
        Instant fourAm = tuesday.plusSeconds(4 * 3600);
        long other = vehicle("IT-BN-C");
        for (Instant t = tuesday; t.isBefore(fourAm); t = t.plusSeconds(60)) {
            report(other, "IT-BN", "ITN-99", t, 0, 1);
        }

        var service = new BrigadeDayService(jdbc, anomalies, Clock.fixed(fourAm, ZoneOffset.UTC));
        assertThat(service.brigades("IT-BNA").brigades()).extracting(BrigadeDto.BrigadeRef::brigade)
                .as("listed whatever day it runs").containsExactly("ITN-01");

        BrigadeDto.Day day = service.day("ITN-01", DAY);
        assertThat(day.trips()).extracting(BrigadeDto.Trip::departure)
                .containsExactly(tuesday.plusSeconds(10 * 60), tuesday.plusSeconds(2 * 3600 + 58 * 60));
        assertThat(day.trips()).extracting(BrigadeDto.Trip::kind).containsExactly("completed", "completed");
        assertThat(day.trips().get(1).runs()).singleElement().satisfies(r -> assertThat(r.toStop()).isEqualTo(4));
        assertThat(service.day("ITN-01", DAY.plusDays(1)).trips()).as("Tuesday's trips are the next night's")
                .extracting(BrigadeDto.Trip::departure).first()
                .isEqualTo(ServiceDay.gtfsTimeZero(DAY.plusDays(2)).plusSeconds(10 * 60));

        BrigadeDto.Schedule schedule = new BrigadeScheduleService(jdbc)
                .schedule("ITN-01", tuesday.plusSeconds(30 * 60));
        assertThat(schedule.serviceDay()).isEqualTo(DAY);
        assertThat(schedule.runs()).extracting(BrigadeDto.Run::departure)
                .containsExactly(tuesday.plusSeconds(10 * 60), tuesday.plusSeconds(2 * 3600 + 58 * 60));
    }

    private long vehicle(String number) {
        return jdbc.queryForObject(
                "INSERT INTO vehicle (vehicle_number, type, agency) VALUES (?, 'BUS', 'BKM') RETURNING id",
                Long.class, number);
    }

    private static Instant local(String hhmmss) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(LocalTime.parse(hhmmss).toSecondOfDay());
    }

    /** Reports every 20 s from {@code start} with this delay: the next stop's sequence number in turn. */
    private void drive(long vehicle, String brigade, String start, long delay, int... nextStops) {
        drive(vehicle, "IT-BD", brigade, local(start), delay, nextStops);
    }

    private void drive(long vehicle, String line, String brigade, Instant start, long delay, int... nextStops) {
        Instant t = start;
        for (int next : nextStops) {
            report(vehicle, line, brigade, t, delay, next);
            t = t.plusSeconds(20);
        }
    }

    private void report(long vehicle, String brigade, Instant t, long delay, int nextStop) {
        report(vehicle, "IT-BD", brigade, t, delay, nextStop);
    }

    private void report(long vehicle, String line, String brigade, Instant t, long delay, int nextStop) {
        double lat = 53.10 + Math.max(0, nextStop - 2) * 0.02 + 0.01;
        writer.write(List.of(new FeedPosition(vehicle, "x", "BUS", "BKM", null, line, null, brigade, line + " 4",
                lat, 23.10 + (lat - 53.10), 0.0, delay, t, List.of(new FeedPosition.UpcomingStop(nextStop,
                line + " " + nextStop, null, null)))), t);
    }
}
