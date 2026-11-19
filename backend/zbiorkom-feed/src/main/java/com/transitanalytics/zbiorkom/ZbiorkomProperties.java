package com.transitanalytics.zbiorkom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

/** Settings for the zbiorkom.live open API. */
@ConfigurationProperties("app.zbiorkom")
public record ZbiorkomProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, Bialystok bialystok) {

    /**
     * @param bbox          bounding box "minLon,minLat,maxLon,maxLat" the positions are requested for
     * @param excludedLines line names (a regular expression, whole name) left out everywhere: not stored, archived
     *                      or shown; empty for none
     */
    public record Bialystok(String bbox, String excludedLines) {

        /** Whether a line is left out. */
        public boolean excluded(String line) {
            return excludedLines != null && !excludedLines.isBlank() && line != null
                    && Pattern.compile(excludedLines).matcher(line).matches();
        }
    }
}
