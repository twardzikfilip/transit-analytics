package com.transitanalytics.service;

import org.slf4j.LoggerFactory;

/** Precomputes slow reports at startup, in the background, so the first visit doesn't wait. */
final class WarmUp {

    private WarmUp() {
    }

    /** Runs {@code task} on a daemon thread; a failure is logged, as the report is then computed on first use. */
    static void inBackground(String name, Class<?> owner, Runnable task) {
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
