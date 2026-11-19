package com.transitanalytics.recorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * transit-recorder: polls the zbiorkom.live Białystok feed every 10 seconds, keeps every response in the raw archive,
 * stores the positions in {@code position_sample} and serves the latest snapshot to the backend's Live map. It runs
 * on its own, so the backend can be restarted without losing any of the feed.
 */
@SpringBootApplication
public class RecorderApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecorderApplication.class, args);
    }
}
