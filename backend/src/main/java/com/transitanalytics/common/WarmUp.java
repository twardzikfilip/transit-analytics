package com.transitanalytics.common;

import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Precomputes slow reports at startup, in the background, so the first visit doesn't wait. */
public final class WarmUp {

    /**
     * Condition for the warm-up listeners: on unless {@code app.warm-up.enabled=false} (integration tests turn it off,
     * as a test context over the real database would otherwise crunch months of data in the background).
     */
    public static final String ENABLED = "@environment.getProperty('app.warm-up.enabled', 'true') == 'true'";

    private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Duration> OWED = ThreadLocal.withInitial(() -> Duration.ZERO);

    private WarmUp() {
    }

    /** Runs {@code task} on a daemon thread; a failure is logged, as the report is then computed on first use. */
    public static void inBackground(String name, Class<?> owner, Runnable task) {
        Thread thread = new Thread(() -> {
            RUNNING.set(true);
            try {
                task.run();
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(owner).warn("{} not precomputed", name, e);
            }
        }, name + "-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    /** Whether this is a background thread's work: nobody waits for it. */
    static boolean running() {
        return RUNNING.get();
    }

    /** Background work owes a pause, so it doesn't keep the processor busy on end. */
    static void pauseLater(Duration duration) {
        OWED.set(OWED.get().plus(duration));
    }

    /** Takes the pauses owed; called where no lock is held that others may wait for. */
    public static void pauseNow() {
        Duration owed = OWED.get();
        if (owed.isZero()) {
            return;
        }
        OWED.remove();
        try {
            Thread.sleep(owed.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
