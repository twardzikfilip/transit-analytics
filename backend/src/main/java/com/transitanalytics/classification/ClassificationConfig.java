package com.transitanalytics.classification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The vehicle and route classifications, configured in {@code classification/*.yml}; routes are reclassified nightly. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({VehicleClassProperties.class, RouteClassProperties.class, OperatorProperties.class})
class ClassificationConfig {
}
