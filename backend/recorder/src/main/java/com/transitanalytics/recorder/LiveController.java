package com.transitanalytics.recorder;

import com.transitanalytics.zbiorkom.LiveSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The latest positions, for the backend's Live map (reachable only inside the host or Docker network). */
@RestController
public class LiveController {

    private final PositionRecorder recorder;

    public LiveController(PositionRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/live/bialystok/positions")
    public LiveSnapshot positions() {
        return recorder.live();
    }
}
