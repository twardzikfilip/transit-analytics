package com.transitanalytics.zbiorkom;

import com.transitanalytics.dto.LivePositionDto;
import com.transitanalytics.service.LineSubtypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Live positions for Białystok: transit-recorder's latest snapshot (it polls zbiorkom.live every 10 seconds); when
 * the recorder can't be reached or its snapshot is too old, straight from zbiorkom.live. Vehicles seen for the first
 * time are registered in the vehicle table either way.
 */
@Service
public class ZbiorkomBialystokService {

    /** The city operator (Białostocka Komunikacja Miejska); zbiorkom reports it as "default". */
    /** A recorder snapshot older than this isn't live any more. */
    static final Duration MAX_AGE = Duration.ofSeconds(60);
    /** The fallback is logged at most this often. */
    static final Duration WARN_EVERY = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(ZbiorkomBialystokService.class);

    private final RecorderClient recorder;
    private final BialystokFeed feed;
    private final LineSubtypes lineSubtypes;
    private final Clock clock;
    private volatile Instant warnedAt = Instant.MIN;

    public ZbiorkomBialystokService(RecorderClient recorder, BialystokFeed feed, LineSubtypes lineSubtypes,
                                    Clock clock) {
        this.recorder = recorder;
        this.feed = feed;
        this.lineSubtypes = lineSubtypes;
        this.clock = clock;
    }

    /** Current position of every vehicle in the Białystok area. */
    public List<LivePositionDto> positions() {
        return snapshot().positions().stream().map(this::toDto).toList();
    }

    private LiveSnapshot snapshot() {
        Instant now = clock.instant();
        String why;
        try {
            LiveSnapshot latest = recorder.latest();
            if (latest.fetchedAt() != null && latest.fetchedAt().isAfter(now.minus(MAX_AGE))) {
                return latest;
            }
            why = "its latest snapshot is from " + latest.fetchedAt();
        } catch (RuntimeException ex) {
            why = ex.toString();
        }
        if (warnedAt.plus(WARN_EVERY).isBefore(now)) {
            warnedAt = now;
            log.warn("Live positions straight from zbiorkom.live, not from transit-recorder: {}", why);
        }
        BialystokFeed.Snapshot direct = feed.poll();
        return new LiveSnapshot(direct.fetchedAt(), direct.positions());
    }

    private LivePositionDto toDto(FeedPosition p) {
        List<LivePositionDto.UpcomingStop> stops = p.upcomingStops() == null ? List.of() : p.upcomingStops().stream()
                .map(s -> new LivePositionDto.UpcomingStop(s.sequence(), s.name(), s.scheduledArrival(),
                        s.scheduledDeparture()))
                .toList();
        return new LivePositionDto(p.vehicleId(), p.vehicleNumber(), p.type(), p.agency(), p.model(), p.routeName(),
                p.routeColor(), p.brigade(), p.headsign(), p.latitude(), p.longitude(), p.bearing(),
                p.delaySeconds(), p.timestamp(), stops, lineSubtypes.of(p.routeName()));
    }
}
