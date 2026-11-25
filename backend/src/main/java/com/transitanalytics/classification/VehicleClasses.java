package com.transitanalytics.classification;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Keeps every bus's class (and capacity, where known) in line with {@code classification/vehicles.yml}, and the company
 * running it with {@code classification/operators.yml}.
 */
@Service
public class VehicleClasses {

    private static final Logger log = LoggerFactory.getLogger(VehicleClasses.class);

    private final VehicleRepository vehicles;
    private final VehicleClassifier classifier;
    private final OperatorProperties operators;

    public VehicleClasses(VehicleRepository vehicles, VehicleClassifier classifier,
                          OperatorProperties operators) {
        this.vehicles = vehicles;
        this.classifier = classifier;
        this.operators = operators;
    }

    /** Classes every bus at startup, before the lines are classified (their capacity counts the big buses). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void applyAll() {
        int changed = 0;
        for (Vehicle v : vehicles.findAll()) {
            if (apply(v)) {
                changed++;
            }
        }
        log.info("Vehicle classes and operators applied; {} vehicles changed", changed);
    }

    /**
     * Classes the buses transit-recorder registered since (it stores a new bus's number, type, agency and model only),
     * every few minutes.
     *
     * @return how many were classed
     */
    @Scheduled(fixedDelayString = "${app.vehicle-classes.sweep-every:5m}",
            initialDelayString = "${app.vehicle-classes.sweep-every:5m}")
    @Transactional
    public int applyToNew() {
        int changed = 0;
        for (Vehicle v : vehicles.findUnclassified()) {
            if (apply(v)) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Vehicle classes and operators applied to {} new vehicles", changed);
        }
        return changed;
    }

    private boolean apply(Vehicle v) {
        boolean changed = apply(v, classifier.classify(v.getModel()));
        String operator = operators.operatingAgency(v.getAgency(), v.getVehicleNumber());
        if (!Objects.equals(operator, v.getOperatingAgency())) {
            v.setOperatingAgency(operator);
            changed = true;
        }
        return changed;
    }

    /**
     * Sets a bus's class from its model's spec, and its capacity when the spec has one (a capacity entered by hand is
     * kept otherwise).
     *
     * @return whether anything changed
     */
    public static boolean apply(Vehicle v, VehicleClassifier.Spec spec) {
        boolean changed = v.getVehicleClass() != spec.vehicleClass();
        v.setVehicleClass(spec.vehicleClass());
        if (spec.capacity() != null && !spec.capacity().equals(v.getCapacity())) {
            v.setCapacity(spec.capacity());
            changed = true;
        }
        return changed;
    }
}
