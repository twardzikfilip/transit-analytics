package com.transitanalytics.brigades;

import com.transitanalytics.data.Reports;
import com.transitanalytics.data.Timetables;
import com.transitanalytics.brigades.BrigadeLearning.Assignment;
import com.transitanalytics.brigades.BrigadeLearning.Learnt;
import com.transitanalytics.brigades.BrigadeLearning.LiveBrigade;
import com.transitanalytics.common.WarmUp;
import com.transitanalytics.domain.CityTime;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.gtfs.GtfsProperties;
import com.transitanalytics.timetable.ServiceDay;
import com.transitanalytics.data.Report;
import com.transitanalytics.data.ScheduledTrip;
import com.transitanalytics.trips.TripAnomalyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Brigades of the routes whose timetable doesn't name them (Wschód Express, Turośń Kościelna: no block ids), learnt
 * from the last weeks' recorded positions ({@link BrigadeLearning}) and stored in {@code trip_brigade} and
 * {@code recorded_brigade_line}. With them, these routes have brigades as BKM's have: in the brigade list, a brigade's
 * day (scheduled and run), Abnormal trips, and the route's brigades and depot runs.
 * <p>
 * Learnt again every night, after a feed's import, and at startup.
 */
@Service
public class RecordedBrigades {

    /** The days learnt from: the last four weeks, a month of each day type. */
    static final int DAYS = 28;

    private static final Logger log = LoggerFactory.getLogger(RecordedBrigades.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Timetables timetables;
    private final Reports reports;
    private final TripAnomalyService anomalies;
    private final GtfsProperties feeds;
    private final Clock clock;

    public RecordedBrigades(JdbcTemplate jdbc, TransactionTemplate transaction, Timetables timetables,
                            Reports reports, TripAnomalyService anomalies, GtfsProperties feeds, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.timetables = timetables;
        this.reports = reports;
        this.anomalies = anomalies;
        this.feeds = feeds;
        this.clock = clock;
    }

    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void atStartup() {
        WarmUp.inBackground("recorded-brigades", RecordedBrigades.class, this::learnAll);
    }

    /** After a feed's import: its trips may have changed. */
    @EventListener
    public void afterImport(GtfsImported imported) {
        if (learnt(imported.feed())) {
            WarmUp.inBackground("recorded-brigades", RecordedBrigades.class, () -> learn(imported.feed()));
        }
    }

    /** Every night, once the service day is over: it adds a day. */
    @Scheduled(cron = "${app.recorded-brigades.refresh-cron:-}", zone = CityTime.ZONE_ID)
    public void nightly() {
        learnAll();
    }

    /** Learns the brigades of every feed whose buses are on the live feed (the city's timetable names its own). */
    public void learnAll() {
        feeds.extra().keySet().stream().filter(this::learnt).forEach(this::learn);
    }

    private boolean learnt(String feed) {
        return feeds.extra().containsKey(feed) && feeds.feed(feed).agency() != null;
    }

    /** A feed's brigades: of each live number on a route, and of each trip (by trip id). */
    record Brigades(Map<LiveBrigade, String> routes, Map<String, Assignment> trips) {
    }

    /**
     * Learns a feed's brigades from the last {@link #DAYS} complete service days and stores them for its trips of
     * those days and the days ahead, unless nothing was recorded (what was learnt before is kept).
     */
    synchronized void learn(String feed) {
        String agency = feeds.feed(feed).agency();
        LocalDate today = ServiceDay.serviceDay(clock.instant());
        BrigadeLearning learning = new BrigadeLearning();
        int recorded = 0;
        for (LocalDate day = today.minusDays(DAYS); day.isBefore(today); day = day.plusDays(1)) {
            List<ScheduledTrip> trips = timetables.trips(day, new String[] {feed}, null);
            if (trips.isEmpty()) {
                continue;
            }
            List<Report> live = reports.live(ServiceDay.start(day), TripAnomalyService.reportsEnd(day, trips), agency);
            if (!live.isEmpty()) {
                learning.add(day, trips, live);
                recorded++;
            }
        }
        Learnt learnt = learning.learnt();
        if (learnt.runs().isEmpty()) {
            log.info("No brigades learnt for feed {}: nothing recorded in the last {} days", feed, DAYS);
            return;
        }
        Brigades brigades = new Brigades(learnt.brigades(), tripBrigades(feed, learnt, today.minusDays(DAYS)));
        if (store(feed, agency, brigades)) {
            anomalies.forgetOutcomes();
            log.info("Brigades of feed {} learnt from {} days: {} brigades running {} runs, {} trips", feed, recorded,
                    learnt.runs().values().stream().map(Assignment::brigade).distinct().count(),
                    learnt.runs().size(), brigades.trips().size());
        }
    }

    /** The brigade of each trip of the feed running from {@code from} on whose run was learnt, by trip id. */
    private Map<String, Assignment> tripBrigades(String feed, Learnt learnt, LocalDate from) {
        LocalDate last = jdbc.queryForObject("SELECT max(date) FROM calendar_date WHERE feed = ?", LocalDate.class,
                feed);
        Map<String, Assignment> trips = new HashMap<>();
        for (LocalDate day = from; last != null && !day.isAfter(last); day = day.plusDays(1)) {
            for (ScheduledTrip trip : timetables.trips(day, new String[] {feed}, null)) {
                Assignment assignment = learnt.of(day, trip);
                if (assignment != null) {
                    trips.putIfAbsent(trip.tripId(), assignment);
                }
            }
        }
        return trips;
    }

    /** Replaces a feed's stored brigades; whether they changed. */
    private boolean store(String feed, String agency, Brigades brigades) {
        Boolean changed = transaction.execute(status -> {
            if (brigades.equals(stored(feed, agency))) {
                return false;
            }
            jdbc.update("DELETE FROM recorded_brigade_line WHERE agency = ?", agency);
            jdbc.update("DELETE FROM trip_brigade b USING trip t WHERE t.id = b.trip_id AND t.feed = ?", feed);
            jdbc.batchUpdate("INSERT INTO recorded_brigade_line (agency, line, live_brigade, brigade) VALUES (?, ?, ?, ?)",
                    brigades.routes().entrySet().stream()
                            .map(e -> new Object[] {agency, e.getKey().route(), e.getKey().live(), e.getValue()})
                            .toList());
            jdbc.batchUpdate("""
                    INSERT INTO trip_brigade (trip_id, brigade, days)
                    SELECT id, ?, ? FROM trip WHERE feed = ? AND gtfs_id = ?""",
                    brigades.trips().entrySet().stream()
                            .map(e -> new Object[] {e.getValue().brigade(), e.getValue().days(), feed, e.getKey()})
                            .toList());
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    /** A feed's brigades as stored. */
    private Brigades stored(String feed, String agency) {
        Map<LiveBrigade, String> brigades = new HashMap<>();
        jdbc.query("SELECT line, live_brigade, brigade FROM recorded_brigade_line WHERE agency = ?",
                rs -> {
                    brigades.put(new LiveBrigade(rs.getString("line"), rs.getString("live_brigade")),
                            rs.getString("brigade"));
                }, agency);
        Map<String, Assignment> trips = new HashMap<>();
        jdbc.query("""
                SELECT t.gtfs_id, b.brigade, b.days FROM trip_brigade b JOIN trip t ON t.id = b.trip_id
                WHERE t.feed = ?""",
                rs -> {
                    trips.put(rs.getString("gtfs_id"), new Assignment(rs.getString("brigade"), rs.getInt("days")));
                }, feed);
        return new Brigades(brigades, trips);
    }
}
