package com.transitanalytics.classification;

import com.transitanalytics.domain.VehicleClass;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * How buses are classed and what they carry, from {@code classification/vehicles.yml}.
 *
 * @param bands          length bands in order: a bus is in the first band it is shorter than; the last has no limit
 * @param doubleDecker   a model name matching this (a regular expression) is a double-decker
 * @param lengthFromName regular expressions whose first group is a length in metres in a model name ("Urbino 15"),
 *                       for models without an entry
 * @param models         known models: the first whose {@code match} is found in the name applies
 */
@ConfigurationProperties("app.vehicle-classes")
public record VehicleClassProperties(List<Band> bands, String doubleDecker, List<String> lengthFromName,
                                     List<Model> models) {

    public VehicleClassProperties {
        bands = bands == null ? List.of() : List.copyOf(bands);
        lengthFromName = lengthFromName == null ? List.of() : List.copyOf(lengthFromName);
        models = models == null ? List.of() : List.copyOf(models);
    }

    /** @param shorterThan metres; null for the last band */
    public record Band(VehicleClass vehicleClass, Double shorterThan) {
    }

    /**
     * @param match        a regular expression found in the model name (case ignored)
     * @param vehicleClass the class outright, else from the length
     * @param length       metres
     * @param capacity     passengers, seated and standing; null when not known
     * @param source       where the figures come from
     */
    public record Model(String match, VehicleClass vehicleClass, Double length, Integer capacity, String source) {
    }
}
