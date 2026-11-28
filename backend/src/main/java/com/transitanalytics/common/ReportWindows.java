package com.transitanalytics.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Resolves the time window a client asked for: {@code to} defaults to now and {@code from} to the first recorded
 * position ("all time"), or a day before {@code to} when nothing is recorded yet.
 */
@Component
public class ReportWindows {

    /** How long the time of the first recording is kept before it is read again. */
    static final Duration FIRST_RECORDING_MAX_AGE = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Instant firstRecording;
    private volatile Instant readAt = Instant.MIN;

    public ReportWindows(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ReportWindow resolve(Instant from, Instant to) {
        Instant end = to != null ? to : clock.instant();
        if (from != null) {
            return new ReportWindow(from, end);
        }
        Instant first = firstRecording();
        return new ReportWindow(first == null || !first.isBefore(end) ? end.minus(Duration.ofDays(1)) : first, end);
    }

    /** The last {@code length} up to now. */
    public ReportWindow last(Duration length) {
        Instant now = clock.instant();
        return new ReportWindow(now.minus(length), now);
    }

    /** When the first position was recorded; null when none is. */
    Instant firstRecording() {
        Instant now = clock.instant();
        if (readAt.plus(FIRST_RECORDING_MAX_AGE).isBefore(now)) {
            Timestamp first = jdbc.queryForObject("SELECT min(observed_at) FROM position_sample", Timestamp.class);
            firstRecording = first == null ? null : first.toInstant();
            readAt = now;
        }
        return firstRecording;
    }
}
