package com.transitanalytics.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Connects the Logback {@code OTEL} appender (see logback-spring.xml) to the OpenTelemetry SDK that
 * Spring Boot configures, so log records are exported over OTLP with their trace context.
 */
@Component
class OtelLogbackInstaller implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OtelLogbackInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
