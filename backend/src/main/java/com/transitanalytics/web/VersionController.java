package com.transitanalytics.web;

import com.transitanalytics.dto.VersionDto;
import com.transitanalytics.zbiorkom.RecorderClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Version", description = "The versions running")
@RestController
@RequestMapping("/api/version")
public class VersionController {

    /** Made by Gradle; missing when started from an IDE. */
    private final ObjectProvider<BuildProperties> build;
    private final RecorderClient recorder;

    public VersionController(ObjectProvider<BuildProperties> build, RecorderClient recorder) {
        this.build = build;
        this.recorder = recorder;
    }

    @Operation(summary = "Versions", description = "The backend's version and release date, and transit-recorder's (null when it "
            + "doesn't answer within 2 s).")
    @GetMapping
    public VersionDto version() {
        BuildProperties app = build.getIfAvailable();
        return new VersionDto(app == null ? new VersionDto.Release("dev", null)
                : new VersionDto.Release(app.getVersion(), app.get("released")), recorder.release());
    }
}
