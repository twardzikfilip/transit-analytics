package com.transitanalytics.service;

import com.transitanalytics.service.TripAnomalies.Report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The reports of the service day running now (and of the day before while its night trips may still be judged),
 * kept in memory: Abnormal trips and Trips judge the running day again every minute, and reading a day's million
 * reports from the database each time takes seconds. Each refresh reads only what was recorded since the last one
 * (and the last {@link ServiceDayCache#REPORTS_ARRIVE} again, for reports stored a little late). When reports appear
 * in the part already read (spooled during a database outage), everything is read again.
 */
final class RecentReports {

    /** The day before is kept this long into the next day: its night trips run until about 04:00. */
    static final Duration KEEP_DAY_BEFORE = Duration.ofHours(3);
    /** A window ending this little after now is still covered (now moves on while a request runs). */
    static final Duration COVERS_AHEAD = Duration.ofMinutes(1);

    /** Reports read this recently aren't refreshed: one request asks for several things. */
    static final Duration REFRESH_AFTER = Duration.ofSeconds(2);

    private final Clock clock;
    /** Reads the reports in {@code [from, to)}. */
    private final BiFunction<Instant, Instant, List<Report>> read;
    /** The minutes (epoch / 60) with any report in {@code [from, to)}. */
    private final BiFunction<Instant, Instant, Set<Long>> readMinutes;
    /** Counts the positions recorded in {@code [from, to)}. */
    private final BiFunction<Instant, Instant, Long> positions;

    /** Where the kept reports start: a service day's start. Null: nothing kept. */
    private Instant start;
    /** Reports are read up to here; the next refresh reads from {@link ServiceDayCache#REPORTS_ARRIVE} before it. */
    private Instant readUntil;
    /** The positions in {@code [start, readUntil - REPORTS_ARRIVE)} when last counted. */
    private long positionsBefore;
    /** See {@link #reading()}. */
    private long reading;
    /** Each vehicle's reports in time order. */
    private final Map<Long, List<Report>> byVehicle = new TreeMap<>();
    private final Set<Long> minutes = new HashSet<>();
    /** One copy of each repeated text (bus numbers, lines, brigades, destinations): a million reports share them. */
    private final Map<String, String> texts = new HashMap<>();

    RecentReports(Clock clock, BiFunction<Instant, Instant, List<Report>> read,
                  BiFunction<Instant, Instant, Set<Long>> readMinutes, BiFunction<Instant, Instant, Long> positions) {
        this.clock = clock;
        this.read = read;
        this.readMinutes = readMinutes;
        this.positions = positions;
    }

    /** Where kept reports start now: today's service day, or the day before during the first hours of today's. */
    static Instant keptFrom(Instant now) {
        LocalDate today = ServiceDay.serviceDay(now);
        Instant todayStart = ServiceDay.start(today);
        return now.isBefore(todayStart.plus(KEEP_DAY_BEFORE)) ? ServiceDay.start(today.minusDays(1)) : todayStart;
    }

    /** Whether the reports in {@code [from, to)} are kept here (else they are read from the database). */
    boolean covers(Instant from, Instant to) {
        Instant now = clock.instant();
        return !from.isBefore(keptFrom(now)) && !to.isAfter(now.plus(COVERS_AHEAD));
    }

    /** The reports in {@code [from, to)}, by vehicle and time; the window must be {@link #covers covered}. */
    synchronized List<Report> reports(Instant from, Instant to) {
        refresh();
        List<Report> list = new ArrayList<>();
        byVehicle.values().forEach(reports -> addBetween(reports, from, to, list));
        return list;
    }

    /** The lines of the kept reports in {@code [from, to)}. */
    synchronized Set<String> lines(Instant from, Instant to) {
        refresh();
        Set<String> lines = new HashSet<>();
        for (List<Report> reports : byVehicle.values()) {
            for (Report r : reports) {
                if (!r.time().isBefore(from) && r.time().isBefore(to)) {
                    lines.add(r.line());
                }
            }
        }
        return lines;
    }

    /** As {@link #reports(Instant, Instant)}, of the vehicles that reported on any of the brigades then. */
    synchronized List<Report> reports(Instant from, Instant to, Collection<String> brigades) {
        refresh();
        Set<String> wanted = new HashSet<>(brigades);
        List<Report> list = new ArrayList<>();
        for (List<Report> reports : byVehicle.values()) {
            List<Report> between = new ArrayList<>();
            addBetween(reports, from, to, between);
            if (between.stream().anyMatch(r -> wanted.contains(r.brigade()))) {
                list.addAll(between);
            }
        }
        return list;
    }

    /** The minutes (epoch / 60) with any report in {@code [from, to)}. */
    synchronized Set<Long> minutes(Instant from, Instant to) {
        refresh();
        long first = Math.floorDiv(from.getEpochSecond(), 60);
        long last = Math.floorDiv(to.getEpochSecond() - 1, 60);
        Set<Long> set = new HashSet<>();
        for (long m : minutes) {
            if (m >= first && m <= last) {
                set.add(m);
            }
        }
        return set;
    }

    /** The minutes (epoch / 60) with any of the kept reports (the timetable's lines) in {@code [from, to)}. */
    synchronized Set<Long> reportMinutes(Instant from, Instant to) {
        refresh();
        Set<Long> set = new HashSet<>();
        for (List<Report> reports : byVehicle.values()) {
            for (Report r : reports) {
                if (!r.time().isBefore(from) && r.time().isBefore(to)) {
                    set.add(r.time().getEpochSecond() / 60);
                }
            }
        }
        return set;
    }

    /**
     * Which reading of the whole kept span this is, after refreshing: it goes up whenever everything is read again
     * (reports stored late, or {@link #forget}), so what was worked out from an earlier one may be out of date.
     */
    synchronized long reading() {
        refresh();
        return reading;
    }

    /** Drops what is kept (the timetable or the learnt brigades changed, so reports read differently). */
    synchronized void forget() {
        start = null;
    }

    /** How many reports are kept. */
    synchronized int size() {
        return byVehicle.values().stream().mapToInt(List::size).sum();
    }

    private void refresh() {
        Instant now = clock.instant();
        Instant from = keptFrom(now);
        if (start != null && start.equals(from) && now.isBefore(readUntil.plus(REFRESH_AFTER))
                && !now.isBefore(readUntil)) {
            return;
        }
        if (start != null && from.isAfter(start) && from.isBefore(readUntil)) {
            // The day before isn't needed any more: drop its reports, keep today's.
            start = from;
            byVehicle.values().forEach(reports -> reports.removeIf(r -> r.time().isBefore(from)));
            byVehicle.values().removeIf(List::isEmpty);
            long first = Math.floorDiv(from.getEpochSecond(), 60);
            minutes.removeIf(m -> m < first);
            positionsBefore = positions.apply(start, readUntil.minus(ServiceDayCache.REPORTS_ARRIVE));
        }
        Instant counted = start == null ? null : readUntil.minus(ServiceDayCache.REPORTS_ARRIVE);
        boolean again = start == null || !start.equals(from)
                || positions.apply(start, counted) != positionsBefore;
        Instant readFrom;
        if (again) {
            reading++;
            start = from;
            byVehicle.clear();
            minutes.clear();
            texts.clear();
            readFrom = from;
        } else {
            readFrom = readUntil.minus(ServiceDayCache.REPORTS_ARRIVE);
        }
        for (Report r : read.apply(readFrom, now)) {
            add(r);
        }
        minutes.addAll(readMinutes.apply(readFrom, now));
        readUntil = now;
        Instant until = readUntil.minus(ServiceDayCache.REPORTS_ARRIVE);
        // Only what is new is counted.
        positionsBefore = again ? positions.apply(start, until) : positionsBefore + positions.apply(counted, until);
    }

    /** Adds a report in its vehicle's time order, unless it is kept already. */
    private void add(Report r) {
        List<Report> reports = byVehicle.computeIfAbsent(r.vehicleId(), k -> new ArrayList<>());
        int i = reports.size();
        while (i > 0 && reports.get(i - 1).time().isAfter(r.time())) {
            i--;
        }
        if (i > 0 && reports.get(i - 1).time().equals(r.time())) {
            return;
        }
        reports.add(i, new Report(r.vehicleId(), text(r.vehicle()), r.time(), text(r.line()), text(r.brigade()),
                text(r.headsign()), r.delay(), r.nextSeq(), r.lat(), r.lon()));
    }

    private String text(String s) {
        return s == null ? null : texts.computeIfAbsent(s, k -> k);
    }

    private static void addBetween(List<Report> reports, Instant from, Instant to, List<Report> into) {
        for (Report r : reports) {
            if (!r.time().isBefore(from) && r.time().isBefore(to)) {
                into.add(r);
            }
        }
    }
}
