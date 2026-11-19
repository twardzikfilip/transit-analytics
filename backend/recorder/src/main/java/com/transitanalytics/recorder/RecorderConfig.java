package com.transitanalytics.recorder;

import com.transitanalytics.recording.PositionSampleWriter;
import com.transitanalytics.recording.RawArchive;
import com.transitanalytics.recording.RecordingWindow;
import com.transitanalytics.zbiorkom.BialystokFeed;
import com.transitanalytics.zbiorkom.VehicleRegistry;
import com.transitanalytics.zbiorkom.ZbiorkomClient;
import com.transitanalytics.zbiorkom.ZbiorkomClients;
import com.transitanalytics.zbiorkom.ZbiorkomProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RecordingProperties.class, ZbiorkomProperties.class})
class RecorderConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Built from Boot's builder, so every call is traced as a client span. */
    @Bean
    ZbiorkomClient zbiorkomClient(RestClient.Builder builder, ZbiorkomProperties properties, JsonMapper json) {
        return ZbiorkomClients.create(builder, properties, json);
    }

    @Bean
    VehicleRegistry vehicleRegistry(JdbcTemplate jdbc) {
        return new VehicleRegistry(jdbc);
    }

    @Bean
    BialystokFeed bialystokFeed(ZbiorkomClient client, ZbiorkomProperties properties, VehicleRegistry vehicles,
                                Clock clock) {
        return new BialystokFeed(client, properties, vehicles, clock);
    }

    @Bean
    PositionSampleWriter positionSampleWriter(JdbcTemplate jdbc) {
        return new PositionSampleWriter(jdbc);
    }

    @Bean
    RawArchive rawArchive(RecordingProperties properties) {
        return new RawArchive(Path.of(properties.archiveDir()).toAbsolutePath());
    }

    @Bean
    RecordingWindow recordingWindow(RecordingProperties properties) {
        return new RecordingWindow(properties.windowStart(), properties.windowEnd(), properties.zone());
    }
}
