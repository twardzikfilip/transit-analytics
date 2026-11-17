package com.transitanalytics.service;

import com.transitanalytics.dto.DelayGenerationDto;
import com.transitanalytics.service.DelayGeneration.Passage;
import com.transitanalytics.service.DelayGeneration.Run;
import com.transitanalytics.service.DelayGeneration.Stop;
import com.transitanalytics.service.DelayGeneration.StopResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DelayGenerationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    /** Stops A–F northwards along longitude 23.15, about 330 m apart. */
    private static final List<StopPlaces.StopRef> STOPS = List.of(
            stop("A(101)", 0), stop("B NŻ (102)", 1), stop("C(103)", 2), stop("D(104)", 3), stop("E(105)", 4),
            stop("F(106)", 5));

    private static StopPlaces.StopRef stop(String name, int i) {
        return new StopPlaces.StopRef(name, 53.10 + i * 0.003, 23.15);
    }

    /** Local time on {@link #DAY} (UTC+1 in November). */
    private static Instant at(String hhmm) {
        return ServiceDay.gtfsTimeZero(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L + Integer.parseInt(hhmm.substring(3)) * 60L);
    }

    /** A bus leaving stops A, B, C, … in turn from {@code start}, two minutes apart, with these delays. */
    private static List<Passage> drive(long vehicle, String line, String brigade, String headsign, String start,
                                       int... delays) {
        List<Passage> passages = new ArrayList<>();
        for (int i = 0; i < delays.length; i++) {
            passages.add(new Passage(vehicle, "V" + vehicle, line, brigade, headsign, i + 1,
                    String.valueOf((char) ('A' + i)), at(start).plusSeconds(120L * i), delays[i],
                    53.10 + i * 0.003 + 0.0005, 23.15));
        }
        return passages;
    }

    private static List<Run> runs(List<Passage> passages) {
        return DelayGeneration.runs(passages, new StopResolver(STOPS));
    }

    @Test
    void matchesFeedStopNamesWithTheTimetables() {
        assertThat(DelayGeneration.matchKey("Wiosenna/Kawaleryjska NŻ (493)"))
                .isEqualTo(DelayGeneration.matchKey("Wiosenna/Kawaleryjska"));
        assertThat(DelayGeneration.matchKey("Sienkiewicza/Rzeka Biała "))
                .isEqualTo(DelayGeneration.matchKey("Sienkiewicza/Rzeka Biała(312)"));
        assertThat(DelayGeneration.matchKey("Lodowa/Chłodna(654)")).isEqualTo("lodowa/chłodna");
        assertThat(DelayGeneration.matchKey("Kuriany I (K)")).isEqualTo("kuriany i (k)");

        StopResolver resolver = new StopResolver(List.of(stop("Rynek(1)", 0), stop("Rynek(2)", 2)));
        Stop near = resolver.resolve("Rynek", 53.1059, 23.15);
        assertThat(near).isEqualTo(new Stop("Rynek", 53.106, 23.15));
        assertThat(resolver.resolve("Rynek", 53.1059, 23.15)).isSameAs(near);
        assertThat(resolver.resolve("Rynek", 53.20, 23.15)).as("too far: where the bus was")
                .isEqualTo(new Stop("Rynek", 53.2, 23.15));
    }

    @Test
    void joinsPassagesIntoRuns() {
        List<Passage> passages = new ArrayList<>();
        passages.addAll(drive(1, "9", "009-01", "Dworzec", "07:00", 0, 30, 60, 90));
        // The same bus back the other way: the stop sequence starts again.
        passages.addAll(drive(1, "9", "009-01", "Rynek", "07:20", 60, 60, 60));
        // Two passages only: too short.
        passages.addAll(drive(1, "9", "009-01", "Dworzec", "08:00", 0, 0));
        // After a gap of 20 minutes, a new run.
        List<Passage> gap = drive(2, "10", "010-01", "Dworzec", "07:00", 0, 0, 0, 0, 0, 0);
        passages.addAll(gap.subList(0, 3));
        passages.addAll(gap.subList(3, 6).stream().map(p -> new Passage(p.vehicleId(), p.vehicle(), p.line(),
                p.brigade(), p.headsign(), p.seq(), p.stop(), p.time().plusSeconds(20 * 60), p.delay(), p.lat(),
                p.lon())).toList());

        List<Run> runs = runs(passages);

        assertThat(runs).extracting(r -> r.vehicleId() + " " + r.headsign() + " " + r.size())
                .containsExactly("1 Dworzec 4", "1 Rynek 3", "2 Dworzec 3", "2 Dworzec 3");
        Run first = runs.get(0);
        assertThat(first.day()).isEqualTo(DAY);
        assertThat(first.gain()).isEqualTo(90);
        assertThat(first.stops()[1].name()).isEqualTo("B");
        assertThat(first.stops()[1].lat()).as("the timetable stop's position").isEqualTo(53.103);
    }

    @Test
    void measuresOnlyConsecutiveStops() {
        Stop a = new Stop("A", 0, 0);
        Stop b = new Stop("B", 0, 1);
        Stop c = new Stop("C", 0, 2);
        Run run = new Run(1, "V1", "9", "009-01", "X", DAY, new Stop[] {a, b, c, a}, new int[] {1, 2, 4, 5},
                new long[4], new int[4]);
        assertThat(run.measured(0)).isTrue();
        assertThat(run.measured(1)).as("stop 3 skipped").isFalse();
        assertThat(run.measured(2)).isTrue();
    }

    @Test
    void placesTimesInPartsOfTheDay() {
        assertThat(DelayGeneration.band(at("06:29").getEpochSecond())).isEqualTo(1);
        assertThat(DelayGeneration.band(at("06:30").getEpochSecond())).isEqualTo(2);
        assertThat(DelayGeneration.band(at("12:00").getEpochSecond())).isEqualTo(3);
        assertThat(DelayGeneration.band(at("16:59").getEpochSecond())).isEqualTo(4);
        assertThat(DelayGeneration.band(at("19:00").getEpochSecond())).isEqualTo(5);
        assertThat(DelayGeneration.band(at("23:30").getEpochSecond())).isEqualTo(6);
        assertThat(DelayGeneration.BANDS).hasSize(7);
        assertThat(DelayGeneration.BANDS.get(0).key()).isEqualTo("all");
    }

    @Test
    void averagesEachStretchOverAllLinesAndPartsOfTheDay() {
        List<Passage> passages = new ArrayList<>();
        // Line 9: 5 runs gaining 60 s from B to C in the morning peak; line 10: 2 runs gaining 0 s at midday.
        for (int i = 0; i < 5; i++) {
            passages.addAll(drive(i, "9", "009-0" + i, "Dworzec", "07:0" + i, 0, 0, 60, 60, 40));
        }
        for (int i = 0; i < 2; i++) {
            passages.addAll(drive(10 + i, "10", "010-0" + i, "Dworzec", "12:0" + i, 0, 0, 0, 0, 0));
        }

        List<Run> runs = runs(passages);
        List<DelayGenerationDto.Stretch> stretches = DelayGeneration.stretches(runs);

        assertThat(stretches).extracting(s -> s.from() + s.to()).containsExactly("AB", "BC", "CD", "DE");
        DelayGenerationDto.Stretch bc = stretches.get(1);
        assertThat(bc.lines()).containsExactly("9", "10");
        assertThat(bc.runs()).containsExactly(7, 0, 5, 2, 0, 0, 0);
        assertThat(bc.meanSeconds()).containsExactly(42.9, null, 60.0, 0.0, null, null, null);
        assertThat(bc.fromLat()).isEqualTo(53.103);
        assertThat(stretches.get(3).meanSeconds().get(0)).as("recovered").isEqualTo(-14.3);

        List<DelayGenerationDto.LineSummary> lines = DelayGeneration.lines(runs, 2);
        assertThat(lines).extracting(DelayGenerationDto.LineSummary::line).containsExactly("9", "10");
        DelayGenerationDto.LineSummary nine = lines.get(0);
        assertThat(nine.runs()).isEqualTo(5);
        assertThat(nine.meanGainSeconds()).isEqualTo(40.0);
        assertThat(nine.medianGainSeconds()).isEqualTo(40);
        assertThat(nine.gainingShare()).isZero();
        assertThat(nine.generatedMinutesPerDay()).as("5 × 40 s over 2 days").isEqualTo(1.7);
        assertThat(nine.worstFrom() + nine.worstTo()).isEqualTo("BC");
        assertThat(nine.worstMeanSeconds()).isEqualTo(60.0);
        assertThat(lines.get(1).worstFrom()).as("no stretch gains").isNull();
    }

    @Test
    void reportsALineByDirectionWithHoursBrigadesAndExamples() {
        List<Passage> passages = new ArrayList<>();
        // Towards Dworzec: 4 runs at 07:xx, the last one gaining 4 minutes between C and D.
        for (int i = 0; i < 4; i++) {
            passages.addAll(drive(i, "9", i < 2 ? "009-01" : "009-02", "Dworzec", "07:1" + i,
                    0, 10, 20, i == 3 ? 260 : 30, i == 3 ? 270 : 40));
        }
        // Towards Rynek: 3 runs of 3 stops; towards Zajezdnia one run, too few to be a direction.
        for (int i = 0; i < 3; i++) {
            passages.addAll(drive(10 + i, "9", "009-03", "Rynek", "08:0" + i, 0, -10, -20));
        }
        passages.addAll(drive(20, "9", "009-04", "Zajezdnia", "09:00", 0, 0, 0));

        var report = DelayGeneration.lineReport("week", at("00:00"), at("23:00"), "9", runs(passages), 1);

        assertThat(report.directions()).extracting(DelayGenerationDto.Direction::headsign)
                .containsExactly("Dworzec", "Rynek");
        DelayGenerationDto.Direction dworzec = report.directions().get(0);
        assertThat(dworzec.runs()).isEqualTo(4);
        assertThat(dworzec.meanGainSeconds()).isEqualTo(97.5);
        assertThat(dworzec.stretches()).extracting(s -> s.from() + s.to()).containsExactly("AB", "BC", "CD", "DE");
        DelayGenerationDto.LineStretch cd = dworzec.stretches().get(2);
        assertThat(cd.fromSeq()).isEqualTo(3);
        assertThat(cd.meanSeconds()).isEqualTo(67.5);
        assertThat(cd.medianSeconds()).isEqualTo(10);
        assertThat(cd.p90Seconds()).isEqualTo(240);
        assertThat(cd.gainingShare()).isEqualTo(0.25);
        assertThat(cd.delayBefore()).isEqualTo(20.0);
        assertThat(cd.byHour().get(7)).isEqualTo(67.5);
        assertThat(cd.byHour().get(8)).isNull();
        assertThat(cd.exampleIds()).hasSize(1);
        var example = report.examples().get(cd.exampleIds().get(0) - 1);
        assertThat(example.vehicle()).isEqualTo("V3");
        assertThat(example.gainSeconds()).isEqualTo(270);
        assertThat(example.stops()).extracting(DelayGenerationDto.ExampleStop::delaySeconds)
                .containsExactly(0, 10, 20, 260, 270);
        assertThat(example.stops().get(0).left()).isEqualTo(at("07:13"));
        assertThat(dworzec.exampleIds()).hasSize(4);
        assertThat(report.examples().get(dworzec.exampleIds().get(0) - 1)).isSameAs(example);

        assertThat(report.brigades()).extracting(b -> b.brigade() + " " + b.runs() + " " + b.meanGainSeconds()
                + " " + b.maxGainSeconds()).containsExactly("009-02 2 155.0 270", "009-01 2 40.0 40");
        assertThat(report.directions().get(1).meanGainSeconds()).as("runs of 3 stops have no gain").isZero();
    }
}
