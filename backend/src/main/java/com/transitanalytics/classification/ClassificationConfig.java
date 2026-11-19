package com.transitanalytics.classification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The vehicle and line classifications, configured in {@code classification/*.yml}; lines are reclassified nightly. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({VehicleClassProperties.class, RouteClassProperties.class, OperatorProperties.class})
class ClassificationConfig {
}
