package com.transitanalytics.security;

import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Failed logins per address, so passwords can't be guessed by trying: after {@link #MAX_FAILURES} within
 * {@link #WINDOW}, the address has to wait until the window is over. By address, not by user: anyone could lock the
 * shared guest out otherwise. Kept in memory.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** At most this many addresses are kept; the oldest windows go first. */
    static final int MAX_ADDRESSES = 10_000;

    private record Failures(Instant since, int count) {
    }

    private final Clock clock;
    private final Map<String, Failures> byAddress = new HashMap<>();

    public LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts an attempt as failed until {@link #succeeded} takes it back (so many at once can't slip through).
     *
     * @throws TooManyLoginsException when the address has to wait
     */
    synchronized void attempt(String address) {
        Instant now = clock.instant();
        String key = key(address);
        Failures failures = byAddress.get(key);
        if (failures == null || !failures.since().plus(WINDOW).isAfter(now)) {
            makeRoom(now);
            byAddress.put(key, new Failures(now, 1));
            return;
        }
        if (failures.count() >= MAX_FAILURES) {
            throw new TooManyLoginsException(Duration.between(now, failures.since().plus(WINDOW)));
        }
        byAddress.put(key, new Failures(failures.since(), failures.count() + 1));
    }

    /** The attempt was no failure. Earlier failures still count: a guest's login must not reset guesses at admin's. */
    synchronized void succeeded(String address) {
        byAddress.computeIfPresent(key(address),
                (k, f) -> f.count() <= 1 ? null : new Failures(f.since(), f.count() - 1));
    }

    private void makeRoom(Instant now) {
        if (byAddress.size() < MAX_ADDRESSES) {
            return;
        }
        byAddress.values().removeIf(f -> !f.since().plus(WINDOW).isAfter(now));
        if (byAddress.size() >= MAX_ADDRESSES) {
            byAddress.entrySet().stream().min(Map.Entry.comparingByValue(
                            (a, b) -> a.since().compareTo(b.since())))
                    .map(Map.Entry::getKey).ifPresent(byAddress::remove);
        }
    }

    /** An IPv6 connection usually has a whole /64 network to itself, so that counts as one address. */
    static String key(String address) {
        if (address == null || !address.contains(":")) {
            return String.valueOf(address);
        }
        try {
            // Only a literal address gets here (it has a colon), so nothing is looked up.
            InetAddress parsed = InetAddress.getByName(address);
            return parsed instanceof Inet6Address
                    ? Arrays.toString(Arrays.copyOf(parsed.getAddress(), 8)) : parsed.getHostAddress();
        } catch (UnknownHostException ex) {
            return address;
        }
    }
}
