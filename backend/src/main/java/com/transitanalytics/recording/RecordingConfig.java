package com.transitanalytics.recording;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RecordingProperties.class)
class RecordingConfig {

    @Bean
    RawArchive rawArchive(RecordingProperties properties) {
        return new RawArchive(Path.of(properties.archiveDir()).toAbsolutePath());
    }

    @Bean
    RecordingWindow recordingWindow(RecordingProperties properties) {
        return new RecordingWindow(properties.windowStart(), properties.windowEnd(), properties.zone());
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
