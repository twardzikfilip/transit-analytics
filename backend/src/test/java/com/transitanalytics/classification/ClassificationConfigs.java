package com.transitanalytics.classification;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/** The classification settings as the application reads them, from the real {@code classification/*.yml}. */
public final class ClassificationConfigs {

    private ClassificationConfigs() {
    }

    public static VehicleClassProperties vehicles() {
        return bind("classification/vehicles.yml", "app.vehicle-classes", VehicleClassProperties.class);
    }

    public static RouteClassProperties routes() {
        return bind("classification/routes.yml", "app.route-classes", RouteClassProperties.class);
    }

    public static OperatorProperties operators() {
        return bind("classification/operators.yml", "app.operators", OperatorProperties.class);
    }

    /** Settings of {@code type} under {@code prefix} in a YAML resource on the class path. */
    public static <T> T bind(String resource, String prefix, Class<T> type) {
        try {
            var sources = new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
            return new Binder(ConfigurationPropertySources.from(sources)).bind(prefix, type).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
