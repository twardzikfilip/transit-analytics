package com.transitanalytics.service;

import com.transitanalytics.dto.LineStatsDto;

import java.util.List;

/**
 * The parts of a weekday the timetable figures are measured in, the same on every page: peak 06:30–08:30 and
 * 15:00–17:00, off-peak 08:30–15:00 and 17:00–20:00. Times are seconds after midnight.
 */
final class Periods {

    /** Peak: 06:30–08:30 and 15:00–17:00. */
    static final List<Window> PEAK = List.of(new Window(6 * 3600 + 1800, 8 * 3600 + 1800),
            new Window(15 * 3600, 17 * 3600));
    /** Off-peak: 08:30–15:00 and 17:00–20:00. */
    static final List<Window> OFF_PEAK = List.of(new Window(8 * 3600 + 1800, 15 * 3600),
            new Window(17 * 3600, 20 * 3600));

    private Periods() {
    }

    /** A part of the service day, in seconds after midnight; {@code to} is exclusive. */
    record Window(int from, int to) {
        int minutes() {
            return (to - from) / 60;
        }

        boolean contains(int seconds) {
            return seconds >= from && seconds < to;
        }

        /** Whether something running from {@code start} to {@code end} is under way in this window. */
        boolean overlaps(int start, int end) {
            return start < to && end > from;
        }

        LineStatsDto.Period toDto() {
            return new LineStatsDto.Period(hhmm(from), hhmm(to));
        }

        private static String hhmm(int seconds) {
            return "%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60);
        }
    }

    /** Whether a time falls in any of the windows. */
    static boolean in(List<Window> windows, int seconds) {
        return windows.stream().anyMatch(w -> w.contains(seconds));
    }

    static List<LineStatsDto.Period> toDto(List<Window> windows) {
        return windows.stream().map(Window::toDto).toList();
    }
}
