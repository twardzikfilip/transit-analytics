package com.transitanalytics.service;

import org.slf4j.LoggerFactory;

/** Precomputes slow reports at startup, in the background, so the first visit doesn't wait. */
public final class WarmUp {

    /**
     * Condition for the warm-up listeners: on unless {@code app.warm-up.enabled=false} (integration tests turn it off,
     * as a test context over the real database would otherwise crunch months of data in the background).
     */
    public static final String ENABLED = "@environment.getProperty('app.warm-up.enabled', 'true') == 'true'";

    private WarmUp() {
    }

    /** Runs {@code task} on a daemon thread; a failure is logged, as the report is then computed on first use. */
    public static void inBackground(String name, Class<?> owner, Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(owner).warn("{} not precomputed", name, e);
            }
        }, name + "-warm-up");
        thread.setDaemon(true);
        thread.start();
    }
}
