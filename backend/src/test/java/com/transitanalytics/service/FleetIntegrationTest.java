package com.transitanalytics.service;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.FleetDto;
import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Three buses of a test operator on Monday 5 January 2099 (UTC+1): A runs line IT1 in the morning and breaks down at
 * 08:00, back on Wednesday; B runs IT1 and IT2; C is never used. Abnormal trips are given; rolled back.
 */
@DbIntegrationTest
@Transactional
class FleetIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);
    private static final String OPERATOR = "IT-FLEET";

    @Autowired
    JdbcTemplate jdbc;

    private static Instant local(String hhmm) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }

    @Test
    void reportsUseModelsBreakdownsAndOneBusInDetail() {
        long a = vehicle("IT-FA", "Solaris Urbino 12");
        long b = vehicle("IT-FB", "Solaris Urbino 12");
        vehicle("IT-FC", "Yutong U12");
        // A: 06:00–07:00 towards Rynek, 07:01–08:00 towards Dworzec; back two days later.
        record(a, "IT1", "IT1-01", "Rynek", local("06:00"), local("07:00"));
        record(a, "IT1", "IT1-01", "Dworzec", local("07:01"), local("08:00"));
        record(a, "IT1", "IT1-01", "Rynek", local("06:00").plus(Duration.ofDays(2)),
                local("06:10").plus(Duration.ofDays(2)));
        // B: line IT1 06:00–06:30, and after a break line IT2 09:00–09:30.
        record(b, "IT1", "IT1-02", "Rynek", local("06:00"), local("06:30"));
        record(b, "IT2", "IT2-01", "Rynek", local("09:00"), local("09:30"));

        var trip = new TripAnomalyDto.Trip("vehicle-changed", "Bus IT-FA wasn't seen again that day.", "IT1-01_1",
                "IT1", "IT1-01", "Dworzec", "Rynek", "Dworzec", local("07:30"), local("08:10"), 10, List.of("IT-FA"),
                1, 10, null, null, null, null, null, null, null, null, null, null, null, List.of(), List.of(), "broken-down", null, null,
                3, null, null, "Lipowa", null, 0, local("08:00"), 20, "vanished", local("08:20"), 0,
                List.of(new TripAnomalyDto.Step(local("08:00"), "Bus IT-FA stops.")),
                List.of(new TripAnomalyDto.Span("IT-FA", "standing", local("08:00"), local("08:20"))));
        TripAnomalyService trips = mock(TripAnomalyService.class);
        when(trips.outcomes(any(), any())).thenReturn(List.of(
                new Outcome("IT1", local("07:30"), local("08:10"), Kind.VEHICLE_CHANGED, trip, List.of(a, b))));
        Instant now = local("12:00").plus(Duration.ofDays(3));
        var fleet = new FleetService(jdbc, Clock.fixed(now, ZoneOffset.UTC), trips);

        ReportWindow day = new ReportWindow(local("03:00"), local("03:00").plus(Duration.ofDays(1)));
        FleetDto.Report report = fleet.report(day, Set.of(OPERATOR));

        assertThat(report.inUse()).isEqualTo(2);
        assertThat(report.fleet()).isEqualTo(3);
        assertThat(report.usage()).extracting(u -> u.vehicle().number() + " " + u.hours() + " " + u.lines())
                .containsExactly("IT-FA 2.0 [IT1]", "IT-FB 1.0 [IT1, IT2]", "IT-FC 0.0 []");
        assertThat(report.models()).extracting(m -> m.model() + " " + m.inUse() + "/" + m.fleet())
                .containsExactly("Solaris Urbino 12 2/2", "Yutong U12 0/1");
        assertThat(report.breakdowns()).singleElement().satisfies(br -> {
            assertThat(br.vehicle().number()).isEqualTo("IT-FA");
            assertThat(br.at()).isEqualTo(local("08:00"));
            assertThat(br.stop()).isEqualTo("Lipowa");
            assertThat(br.returned()).isEqualTo(local("06:00").plus(Duration.ofDays(2)));
            assertThat(br.daysOut()).isEqualTo(1.9);
        });
        assertThat(report.agencies()).extracting(FleetDto.Agency::name).contains(OPERATOR);

        FleetDto.VehicleDetail detail = fleet.vehicle(a, new ReportWindow(local("03:00"),
                local("03:00").plus(Duration.ofDays(3))));
        assertThat(detail.hours()).isEqualTo(2.2);
        assertThat(detail.lines()).containsExactly("IT1");
        assertThat(detail.days()).extracting(FleetDto.Day::day).containsExactly(DAY, DAY.plusDays(2));
        assertThat(detail.days().get(0).runs()).extracting(FleetDto.Run::headsign).containsExactly("Rynek", "Dworzec");
        assertThat(detail.incidents()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("vehicle-changed");
            assertThat(i.day()).isEqualTo(DAY);
            // Told step by step, with a time line, on the bus's page.
            assertThat(i.story()).extracting(TripAnomalyDto.Step::text).containsExactly("Bus IT-FA stops.");
            assertThat(i.timeline()).extracting(TripAnomalyDto.Span::kind).containsExactly("standing");
            // Where it went over the time line, 2 min either side: its reports at 07:58, 07:59 and 08:00.
            assertThat(i.paths()).singleElement().satisfies(p -> {
                assertThat(p.vehicle()).isEqualTo("IT-FA");
                assertThat(p.points()).hasSize(3).allSatisfy(pt -> assertThat(pt).containsExactly(53.1, 23.1));
            });
        });
        assertThat(detail.lastSeen()).isNotNull();
        assertThat(fleet.vehicle(b, day).incidents()).as("B only took over").isEmpty();
        assertThatThrownBy(() -> fleet.vehicle(-1, day)).isInstanceOf(NotFoundException.class);
    }

    private long vehicle(String number, String model) {
        return jdbc.queryForObject("""
                INSERT INTO vehicle (vehicle_number, type, agency, model) VALUES (?, 'BUS', ?, ?) RETURNING id""",
                Long.class, number, OPERATOR, model);
    }

    /** A report every minute from {@code from} to {@code to}, inclusive. */
    private void record(long vehicle, String line, String brigade, String headsign, Instant from, Instant to) {
        for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(60)) {
            jdbc.update("""
                    INSERT INTO position_sample (observed_at, fetched_at, vehicle_id, route_name, brigade, headsign,
                                                 latitude, longitude)
                    VALUES (?, ?, ?, ?, ?, ?, 53.1, 23.1)""",
                    Timestamp.from(t), Timestamp.from(t), vehicle, line, brigade, headsign);
        }
    }
}
