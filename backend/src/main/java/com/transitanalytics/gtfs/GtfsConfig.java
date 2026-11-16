package com.transitanalytics.gtfs;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GtfsProperties.class)
class GtfsConfig {

    /** Built from Boot's builder so downloads are traced; follows redirects (the Białystok URL redirects). */
    @Bean
    GtfsDownloader gtfsDownloader(RestClient.Builder builder, GtfsProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return new GtfsDownloader(builder.requestFactory(requestFactory).build(),
                properties.maxDownloadSize().toBytes());
    }
}
