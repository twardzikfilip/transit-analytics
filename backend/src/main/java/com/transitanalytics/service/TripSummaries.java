package com.transitanalytics.service;

import com.transitanalytics.dto.TripAnomalyDto;
import com.transitanalytics.service.TripAnomalies.Kind;
import com.transitanalytics.service.TripAnomalies.Outcome;
import com.transitanalytics.service.TripHandovers.Fate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Part of {@link TripAnomalies}: a window's outcomes counted, overall and per line.
 */
final class TripSummaries {

    private TripSummaries() {
    }

    // ---- The window -----------------------------------------------------------------------------------------

    /** Outcomes counted. */
    static TripAnomalyDto.Summary summary(List<Outcome> outcomes) {
        int[] n = counts(outcomes);
        return new TripAnomalyDto.Summary(outcomes.size(), outcomes.size() - n[Kind.NOT_RECORDED.ordinal()]
                - n[Kind.PENDING.ordinal()], n[Kind.COMPLETED.ordinal()], n[Kind.DETOUR.ordinal()],
                n[Kind.VEHICLE_CHANGED.ordinal()], n[Kind.NO_SIGNAL.ordinal()], n[Kind.SHORT_TURNED.ordinal()], n[Kind.LOST.ordinal()], n[Kind.MISSED.ordinal()],
                n[Kind.CANCELLED.ordinal()], n[Kind.NOT_RECORDED.ordinal()], n[Kind.PENDING.ordinal()],
                changed(outcomes, Fate.BROKEN_DOWN), changed(outcomes, Fate.REASSIGNED));
    }

    /** Trips run with a change of bus whose replaced bus met this fate. */
    private static int changed(List<Outcome> outcomes, Fate fate) {
        return (int) outcomes.stream().filter(o -> o.kind() == Kind.VEHICLE_CHANGED
                && fate.key.equals(o.trip().changeFate())).count();
    }

    /** Outcomes per line, the lines with the most abnormal trips first. */
    static List<TripAnomalyDto.LineCounts> lines(List<Outcome> outcomes) {
        Map<String, List<Outcome>> byLine = outcomes.stream().filter(o -> o.kind().judged())
                .collect(Collectors.groupingBy(Outcome::line));
        return byLine.entrySet().stream().map(e -> {
                    int[] n = counts(e.getValue());
                    return new TripAnomalyDto.LineCounts(e.getKey(), e.getValue().size(), n[Kind.COMPLETED.ordinal()],
                            n[Kind.DETOUR.ordinal()], n[Kind.VEHICLE_CHANGED.ordinal()], n[Kind.NO_SIGNAL.ordinal()], n[Kind.SHORT_TURNED.ordinal()],
                            n[Kind.LOST.ordinal()],
                            n[Kind.MISSED.ordinal()], n[Kind.CANCELLED.ordinal()]);
                })
                .sorted(Comparator.comparingInt((TripAnomalyDto.LineCounts c) -> c.completed() - c.judged())
                        .thenComparing(TripAnomalyDto.LineCounts::line, TimetableMath.LINE_ORDER))
                .toList();
    }

    private static int[] counts(List<Outcome> outcomes) {
        int[] n = new int[Kind.values().length];
        outcomes.forEach(o -> n[o.kind().ordinal()]++);
        return n;
    }
}
