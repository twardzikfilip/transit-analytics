package com.transitanalytics.buslanes;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BusLaneProperties.class)
class BusLaneConfig {

    /** Overpass is called through Boot's builder (so calls are traced), with the configured timeout. */
    @Bean
    BusLanes busLanes(BusLaneProperties properties, RestClient.Builder builder, JsonMapper json, Clock clock) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requests.setReadTimeout(properties.timeout());
        return new BusLanes(properties, builder.clone().requestFactory(requests).build(), json, clock);
    }
}
