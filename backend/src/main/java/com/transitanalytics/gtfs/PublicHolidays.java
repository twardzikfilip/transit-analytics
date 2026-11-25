package com.transitanalytics.gtfs;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/** Poland's public holidays, Christmas Eve (one since 2025) included. */
public final class PublicHolidays {

    private static final Set<MonthDay> FIXED = Set.of(MonthDay.of(1, 1), MonthDay.of(1, 6), MonthDay.of(5, 1),
            MonthDay.of(5, 3), MonthDay.of(8, 15), MonthDay.of(11, 1), MonthDay.of(11, 11), MonthDay.of(12, 24),
            MonthDay.of(12, 25), MonthDay.of(12, 26));

    private PublicHolidays() {
    }

    public static boolean isHoliday(LocalDate day) {
        LocalDate easter = easter(day.getYear());
        // Easter Monday and Corpus Christi; Easter and Pentecost are Sundays anyway.
        return FIXED.contains(MonthDay.from(day)) || day.equals(easter) || day.equals(easter.plusDays(1))
                || day.equals(easter.plusDays(49)) || day.equals(easter.plusDays(60));
    }

    /** Easter Sunday (the Gregorian calendar's rule, as computed by Meeus, Jones and Butcher). */
    static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = (19 * a + b - b / 4 - (b - (b + 8) / 25 + 1) / 3 + 15) % 30;
        int e = (32 + 2 * (b % 4) + 2 * (c / 4) - d - c % 4) % 7;
        int f = d + e - 7 * ((a + 11 * d + 22 * e) / 451) + 114;
        return LocalDate.of(year, f / 31, f % 31 + 1);
    }
}
