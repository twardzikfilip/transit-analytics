package com.transitanalytics.service;

import com.transitanalytics.service.Periods.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small calculations shared by the timetable pages (Line statistics, Routes in detail, Important corridors). */
final class TimetableMath {

    private TimetableMath() {
    }

    /** Numbers in numeric order, then names with letters (N1, N2, …). */
    static final Comparator<String> LINE_ORDER = (a, b) -> {
        boolean na = a.chars().allMatch(Character::isDigit);
        boolean nb = b.chars().allMatch(Character::isDigit);
        if (na && nb) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        return na != nb ? (na ? -1 : 1) : a.compareTo(b);
    };

    static double round(double value, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(value * f) / f;
    }

    /** Nearest-rank percentile ({@code p} 0–1) of sorted values; 0 when there are none. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1))];
    }

    /** The most common value; ties go to the smallest one, nulls last. */
    static String mostFrequent(List<String> values) {
        Map<String, Long> counts = new HashMap<>();
        values.forEach(v -> counts.merge(v, 1L, Long::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey,
                        Comparator.nullsFirst(Comparator.<String>reverseOrder())))
                .map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Average minutes between departures: the windows' total minutes divided by the departures in them, per
     * direction, then averaged over the directions with departures then. Null when there are none.
     *
     * @param departuresByDirection departure times (seconds after midnight) per direction
     */
    static Double interval(Map<Integer, List<Integer>> departuresByDirection, List<Window> windows) {
        int minutes = windows.stream().mapToInt(Window::minutes).sum();
        List<Double> perDirection = new ArrayList<>();
        for (List<Integer> times : departuresByDirection.values()) {
            long count = times.stream().filter(t -> Periods.in(windows, t)).count();
            if (count > 0) {
                perDirection.add((double) minutes / count);
            }
        }
        return perDirection.isEmpty() ? null
                : round(perDirection.stream().mapToDouble(d -> d).average().orElse(0), 1);
    }

    /**
     * How evenly departures are spread: the coefficient of variation of the gaps between consecutive departures
     * (population standard deviation / mean; 0 = perfectly even). Computed per direction and per window, so the
     * break between two windows is never a gap, then averaged weighted by the number of gaps. A direction and window
     * needs at least 3 gaps; null when none qualifies.
     *
     * <p>A useful reading: for passengers arriving at random, the expected wait is mean gap / 2 × (1 + CV²).
     */
    static Double regularity(Map<Integer, List<Integer>> departuresByDirection, List<Window> windows) {
        double weightedCv = 0;
        int gapCount = 0;
        for (List<Integer> times : departuresByDirection.values()) {
            for (Window w : windows) {
                List<Integer> in = times.stream().filter(w::contains).sorted().toList();
                if (in.size() < 4) {
                    continue;
                }
                double[] gaps = new double[in.size() - 1];
                for (int i = 1; i < in.size(); i++) {
                    gaps[i - 1] = in.get(i) - in.get(i - 1);
                }
                double mean = Arrays.stream(gaps).average().orElse(0);
                if (mean <= 0) {
                    continue;
                }
                double variance = Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0);
                weightedCv += Math.sqrt(variance) / mean * gaps.length;
                gapCount += gaps.length;
            }
        }
        return gapCount == 0 ? null : round(weightedCv / gapCount, 2);
    }
}
