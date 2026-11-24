package com.transitanalytics.security;

import java.time.Duration;

/** Too many failed logins from an address: it has to wait. */
public class TooManyLoginsException extends RuntimeException {

    private final Duration wait;

    public TooManyLoginsException(Duration wait) {
        super("Too many failed logins; try again in " + Math.max(1, (wait.toSeconds() + 59) / 60) + " min");
        this.wait = wait;
    }

    public long waitSeconds() {
        return Math.max(1, wait.toSeconds());
    }
}
