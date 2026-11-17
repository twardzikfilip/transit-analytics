package com.transitanalytics.service;

import com.transitanalytics.dto.VehicleStatsDto.Incident;
import com.transitanalytics.dto.VehicleStatsDto.LineDay;
import com.transitanalytics.dto.VehicleStatsDto.Usage;
import com.transitanalytics.dto.VehicleStatsDto.Vehicle;
import com.transitanalytics.service.VehicleIncidents.Run;
import com.transitanalytics.service.VehicleStatsService.Segment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VehicleStatsServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private static final Vehicle A = new Vehicle(1, "230", "Solaris Urbino 12", "BKM");
    private static final Vehicle B = new Vehicle(2, "009", "Yutong U12", "BKM");

    /** Brigade 9-01: 06:00–08:00, a break, 08:40–10:00 local time (UTC+1 in November). */
    private static final Map<LocalDate, Map<String, List<Run>>> SCHEDULE = Map.of(DAY,
            Map.of("9-01", List.of(new Run(6 * 3600, 8 * 3600), new Run(8 * 3600 + 40 * 60, 10 * 3600))));

    @Test
    void sumsHoursDaysAndLinesPerVehicle() {
        List<Usage> usage = VehicleStatsService.usage(List.of(
                segment(A, DAY, "10-01", "10", "05:00", "06:00", null),
                segment(A, DAY, "9-01", "9", "06:00", "07:30", null),
                segment(A, DAY.plusDays(1), "9-02", "9", "05:00", "05:30", null),
                segment(B, DAY, "9-01", "9", "07:00", "07:15", null)));

        assertThat(usage).hasSize(2);
        Usage a = usage.get(0);
        assertThat(a.hours()).isEqualTo(3.0);
        assertThat(a.days()).isEqualTo(2);
        assertThat(a.lines()).as("in line order").containsExactly("9", "10");
        assertThat(a.firstSeen()).isEqualTo(local("05:00"));
        assertThat(a.lastSeen()).isEqualTo(Instant.parse("2026-11-17T04:30:00Z"));
        assertThat(usage.get(1).hours()).isEqualTo(0.3);
    }

    @Test
    void listsDaysWithTwoOrMoreLinesInTheOrderServed() {
        List<LineDay> days = VehicleStatsService.lineDays(List.of(
                segment(A, DAY, "9-01", "9", "06:00", "07:00", null),
                segment(A, DAY, "10-01", "10", "05:00", "05:50", null),
                segment(A, DAY, "9-03", "9", "08:00", "09:00", null),
                segment(B, DAY, "9-02", "9", "06:00", "07:00", null)));

        assertThat(days).singleElement().satisfies(d -> {
            assertThat(d.vehicle()).isEqualTo(A);
            assertThat(d.lines()).extracting(s -> s.line() + " " + s.brigade() + " " + s.hours())
                    .containsExactly("10 10-01 0.8", "9 9-01, 9-03 2.0");
        });
    }

    @Test
    void reportsAVehicleThatDisappearedMidServiceAndWhoTookOver() {
        List<Incident> incidents = VehicleStatsService.incidents(List.of(
                segment(A, DAY, "9-01", "9", "06:00", "06:30", 60),
                segment(B, DAY, "9-01", "9", "06:45", "10:00", 0)), SCHEDULE);

        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.vehicle()).isEqualTo(A);
            assertThat(i.kind()).isEqualTo("disappeared");
            assertThat(i.scheduledUntil()).isEqualTo(local("08:00"));
            assertThat(i.scheduledEnd()).isEqualTo(local("10:00"));
            assertThat(i.replacedBy()).isEqualTo(B);
            assertThat(i.replacedAt()).isEqualTo(local("06:45"));
        });
    }

    @Test
    void reportsAVehicleThatSwitchedToAnotherLine() {
        List<Incident> incidents = VehicleStatsService.incidents(List.of(
                segment(A, DAY, "9-01", "9", "06:00", "06:30", 0),
                segment(A, DAY, "10-04", "10", "06:40", "10:00", 0)), SCHEDULE);

        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("switched");
            assertThat(i.nextLine()).isEqualTo("10");
            assertThat(i.nextBrigade()).isEqualTo("10-04");
            assertThat(i.replacedBy()).isNull();
        });
    }

    @Test
    void ignoresWhatIsScheduledAnUnknownBrigadeAndGapsInRecording() {
        assertThat(VehicleStatsService.incidents(List.of(
                // Under another brigade on the same line: it went on serving the line.
                segment(A, DAY, "9-01", "9", "06:00", "06:30", 0),
                segment(A, DAY, "9-07", "9", "06:31", "10:00", 0)), SCHEDULE)).isEmpty();
        assertThat(VehicleStatsService.incidents(List.of(
                // 50 minutes late is a feed error: compared at 07:45 (near the break), not at 06:55 (mid-run).
                segment(A, DAY, "9-01", "9", "06:00", "07:45", 3000),
                segment(B, DAY, "9-01", "9", "08:40", "10:00", 0)), SCHEDULE)).isEmpty();
        assertThat(VehicleStatsService.incidents(List.of(
                // Last seen 08:00, 10 minutes late: it was running to 07:50, at the end of its run before the break.
                segment(A, DAY, "9-01", "9", "06:00", "08:00", 600),
                segment(B, DAY, "9-01", "9", "08:40", "10:00", 0)), SCHEDULE)).isEmpty();
        assertThat(VehicleStatsService.incidents(List.of(
                segment(A, DAY, "6", "406", "06:00", "06:30", 0),
                segment(B, DAY, "9-02", "9", "06:00", "10:00", 0)), SCHEDULE)).as("not in the timetable").isEmpty();
        assertThat(VehicleStatsService.incidents(List.of(
                segment(A, DAY, "9-01", "9", "06:00", "06:30", 0),
                segment(B, DAY, "9-02", "9", "06:00", "06:35", 0)), SCHEDULE)).as("recording stopped").isEmpty();
    }

    @Test
    void acceptsOnlyDayWeekOrMonth() {
        assertThat(VehicleStatsService.Window.of("Week")).isEqualTo(VehicleStatsService.Window.WEEK);
        assertThatThrownBy(() -> VehicleStatsService.Window.of("year")).hasMessageContaining("day, week or month");
        assertThatThrownBy(() -> VehicleStatsService.Window.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A segment from {@code from} to {@code to}, local time on its day, in service the whole time. */
    private static Segment segment(Vehicle v, LocalDate day, String brigade, String line, String from, String to,
                                   Integer delay) {
        Instant first = local(day, from);
        Instant last = local(day, to);
        return new Segment(v, day, brigade, line, first, last, last.getEpochSecond() - first.getEpochSecond(), delay,
                "Rynek");
    }

    private static Instant local(String hhmm) {
        return local(DAY, hhmm);
    }

    private static Instant local(LocalDate day, String hhmm) {
        return ServiceDay.gtfsTimeZero(day).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }
}
