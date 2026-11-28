package com.transitanalytics.timetable;

import com.transitanalytics.domain.CityTime;
import com.transitanalytics.dto.RouteStatsDto;

import java.util.List;

/**
 * The parts of a weekday the timetable figures are measured in, the same on every page ({@link CityTime#PEAK},
 * {@link CityTime#OFF_PEAK}). Times are seconds after midnight.
 */
public final class Periods {

    public static final List<Window> PEAK = CityTime.PEAK.stream().map(Window::parse).toList();
    public static final List<Window> OFF_PEAK = CityTime.OFF_PEAK.stream().map(Window::parse).toList();

    private Periods() {
    }

    /** A part of the service day, in seconds after midnight; {@code to} is exclusive. */
    public record Window(int from, int to) {

        /** From "HH:MM-HH:MM". */
        static Window parse(String window) {
            String[] ends = window.trim().split("-");
            return new Window(seconds(ends[0]), seconds(ends[1]));
        }

        private static int seconds(String hhmm) {
            String[] parts = hhmm.trim().split(":");
            return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60;
        }

        int minutes() {
            return (to - from) / 60;
        }

        boolean contains(int seconds) {
            return seconds >= from && seconds < to;
        }

        /** Whether something running from {@code start} to {@code end} is under way in this window. */
        public boolean overlaps(int start, int end) {
            return start < to && end > from;
        }

        public RouteStatsDto.Period toDto() {
            return new RouteStatsDto.Period(hhmm(from), hhmm(to));
        }

        private static String hhmm(int seconds) {
            return "%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60);
        }
    }

    /** Whether a time falls in any of the windows. */
    public static boolean in(List<Window> windows, int seconds) {
        return windows.stream().anyMatch(w -> w.contains(seconds));
    }

    public static List<RouteStatsDto.Period> toDto(List<Window> windows) {
        return windows.stream().map(Window::toDto).toList();
    }
}
