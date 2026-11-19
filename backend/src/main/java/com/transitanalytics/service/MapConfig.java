package com.transitanalytics.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(MapProperties.class)
class MapConfig {

    /** Built from Boot's builder (so calls are traced), with the configured timeout. */
    @Bean
    DepotRouter depotRouter(MapProperties map, RestClient.Builder builder, JsonMapper json) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(map.routing().timeout());
        requests.setReadTimeout(map.routing().timeout());
        return new DepotRouter(map, builder.requestFactory(requests), json);
    }
}
