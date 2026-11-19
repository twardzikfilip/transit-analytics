package com.transitanalytics.zbiorkom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({ZbiorkomProperties.class, RecorderProperties.class})
class ZbiorkomConfig {

    /** Built from Boot's builder, so every call is traced as a client span. */
    @Bean
    ZbiorkomClient zbiorkomClient(RestClient.Builder builder, ZbiorkomProperties properties, JsonMapper json) {
        return ZbiorkomClients.create(builder.clone(), properties, json);
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

    /** Short timeouts: when the recorder doesn't answer quickly, the Live map asks zbiorkom.live instead. */
    @Bean
    RecorderClient recorderClient(RestClient.Builder builder, RecorderProperties properties, JsonMapper json) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.timeout());
        requests.setReadTimeout(properties.timeout());
        return new RecorderClient(builder.clone().baseUrl(properties.url()).requestFactory(requests).build(), json);
    }
}
