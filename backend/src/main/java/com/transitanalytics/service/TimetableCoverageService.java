package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.TimetableCoverageDto;
import com.transitanalytics.gtfs.GtfsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.transitanalytics.domain.CityTime.ZONE_ID;

/** Until when the timetables run: a feed whose publisher stops renewing it ends without any import failing. */
@Service
public class TimetableCoverageService {

    /** The other operators' feeds cover two weeks at a time, so this leaves time to react without warning always. */
    static final int WARN_DAYS = 5;

    private static final Logger log = LoggerFactory.getLogger(TimetableCoverageService.class);

    private final JdbcTemplate jdbc;
    private final GtfsProperties properties;
    private final Clock clock;

    public TimetableCoverageService(JdbcTemplate jdbc, GtfsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Every configured feed: the city's first, then the others by key. */
    @Transactional(readOnly = true)
    public TimetableCoverageDto coverage() {
        LocalDate today = ServiceDay.serviceDay(clock.instant());
        Map<String, LocalDate> last = new HashMap<>();
        jdbc.query("""
                SELECT c.feed, max(c.date) AS last FROM calendar_date c
                WHERE c.exception_type = 1
                  AND NOT EXISTS (SELECT 1 FROM assumed_timetable_day a WHERE a.feed = c.feed AND a.date = c.date)
                  AND EXISTS (SELECT 1 FROM trip t WHERE t.feed = c.feed AND t.service_id = c.service_id)
                GROUP BY c.feed""", rs -> {
            last.put(rs.getString("feed"), rs.getObject("last", LocalDate.class));
        });
        Map<String, List<LocalDate>> assumed = new HashMap<>();
        jdbc.query("SELECT feed, date FROM assumed_timetable_day ORDER BY date", rs -> {
            assumed.computeIfAbsent(rs.getString("feed"), k -> new ArrayList<>())
                    .add(rs.getObject("date", LocalDate.class));
        });
        List<String> keys = new ArrayList<>(List.of(TimetableFeed.MAIN));
        keys.addAll(new TreeSet<>(properties.extra().keySet()));
        return new TimetableCoverageDto(today, WARN_DAYS,
                keys.stream().map(key -> feed(key, last.get(key), assumed.getOrDefault(key, List.of()), today)).toList());
    }

    static TimetableCoverageDto.Feed feed(String key, LocalDate lastDay, List<LocalDate> assumed, LocalDate today) {
        if (lastDay == null) {
            return new TimetableCoverageDto.Feed(key, null, null, true, assumed);
        }
        int left = (int) ChronoUnit.DAYS.between(today, lastDay);
        return new TimetableCoverageDto.Feed(key, lastDay, left, left <= WARN_DAYS, assumed);
    }

    /** After the nightly imports: says in the log which timetables are ending. */
    @Scheduled(cron = "0 0 5 * * *", zone = ZONE_ID)
    void warn() {
        for (TimetableCoverageDto.Feed feed : coverage().feeds()) {
            if (feed.ending()) {
                log.warn("Timetable {}: last day {}", feed.feed(), feed.lastDay() == null ? "none, not loaded" : feed.lastDay());
            }
        }
    }
}
