package com.transitanalytics.dto;

/**
 * The versions running.
 *
 * @param backend  the app's (backend and frontend are released together)
 * @param recorder transit-recorder's; null when it doesn't answer
 */
public record VersionDto(Release backend, Release recorder) {

    /** @param released the day, e.g. "2026-11-24"; null when unknown */
    public record Release(String version, String released) {
    }
}
