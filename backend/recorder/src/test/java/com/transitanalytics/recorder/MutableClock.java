package com.transitanalytics.recorder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test can move. */
final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
