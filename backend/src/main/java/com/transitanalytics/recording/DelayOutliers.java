package com.transitanalytics.recording;

/** Position reports later than this are treated as outliers: most likely feed errors, not real delays. */
public final class DelayOutliers {

    /** 30 minutes. */
    public static final int THRESHOLD_SECONDS = 30 * 60;

    private DelayOutliers() {
    }
}
