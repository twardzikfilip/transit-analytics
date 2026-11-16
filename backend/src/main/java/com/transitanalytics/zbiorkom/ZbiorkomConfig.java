package com.transitanalytics.zbiorkom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ZbiorkomProperties.class)
class ZbiorkomConfig {

    /** Built from Boot's builder, so every call is traced as a client span. */
    @Bean
    ZbiorkomClient zbiorkomClient(RestClient.Builder builder, ZbiorkomProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return new ZbiorkomClient(builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build());
    }
}
