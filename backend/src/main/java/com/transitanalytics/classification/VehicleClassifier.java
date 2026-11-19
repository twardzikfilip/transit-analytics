package com.transitanalytics.classification;

import com.transitanalytics.domain.VehicleClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classes a bus by its model name, by the rules in {@link VehicleClassProperties}. */
@Component
public class VehicleClassifier {

    /** What is known of a model: its class, length and capacity; any may be null. */
    public record Spec(VehicleClass vehicleClass, Double length, Integer capacity) {
        static final Spec UNKNOWN = new Spec(null, null, null);
    }

    private record CompiledModel(Pattern match, VehicleClassProperties.Model model) {
    }

    private final List<VehicleClassProperties.Band> bands;
    private final Pattern doubleDecker;
    private final List<Pattern> lengthFromName;
    private final List<CompiledModel> models;

    public VehicleClassifier(VehicleClassProperties properties) {
        this.bands = properties.bands();
        this.doubleDecker = properties.doubleDecker() == null ? null
                : Pattern.compile(properties.doubleDecker(), Pattern.CASE_INSENSITIVE);
        this.lengthFromName = properties.lengthFromName().stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
        this.models = properties.models().stream()
                .map(m -> new CompiledModel(Pattern.compile(m.match(), Pattern.CASE_INSENSITIVE), m)).toList();
    }

    /** The class, length and capacity of a model; unknown parts are null. */
    public Spec classify(String model) {
        if (model == null || model.isBlank()) {
            return Spec.UNKNOWN;
        }
        VehicleClassProperties.Model known = models.stream().filter(m -> m.match().matcher(model).find())
                .map(CompiledModel::model).findFirst().orElse(null);
        Double length = known != null && known.length() != null ? known.length() : lengthFromName(model);
        VehicleClass vehicleClass;
        if (doubleDecker != null && doubleDecker.matcher(model).find()) {
            vehicleClass = VehicleClass.DOUBLE_DECKER;
        } else if (known != null && known.vehicleClass() != null) {
            vehicleClass = known.vehicleClass();
        } else {
            vehicleClass = band(length);
        }
        return new Spec(vehicleClass, length, known == null ? null : known.capacity());
    }

    private Double lengthFromName(String model) {
        for (Pattern p : lengthFromName) {
            Matcher m = p.matcher(model);
            if (m.find()) {
                return Double.parseDouble(m.group(1).replace(',', '.'));
            }
        }
        return null;
    }

    private VehicleClass band(Double length) {
        if (length == null) {
            return null;
        }
        return bands.stream().filter(b -> b.shorterThan() == null || length < b.shorterThan())
                .map(VehicleClassProperties.Band::vehicleClass).findFirst().orElse(null);
    }
}
