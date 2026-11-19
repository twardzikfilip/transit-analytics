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

/** Keeps every bus's class (and capacity, where known) in line with {@code classification/vehicles.yml}. */
@Service
public class VehicleClasses {

    private static final Logger log = LoggerFactory.getLogger(VehicleClasses.class);

    private final VehicleRepository vehicles;
    private final VehicleClassifier classifier;

    public VehicleClasses(VehicleRepository vehicles, VehicleClassifier classifier) {
        this.vehicles = vehicles;
        this.classifier = classifier;
    }

    /** Classes every bus at startup, before the lines are classified (their capacity counts the big buses). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void applyAll() {
        int changed = 0;
        for (Vehicle v : vehicles.findAll()) {
            if (apply(v, classifier.classify(v.getModel()))) {
                changed++;
            }
        }
        log.info("Vehicle classes applied; {} vehicles changed", changed);
    }

    /**
     * Classes the buses transit-recorder registered since (it stores a new bus's number, type and model only), every
     * few minutes.
     *
     * @return how many were classed
     */
    @Scheduled(fixedDelayString = "${app.vehicle-classes.sweep-every:5m}",
            initialDelayString = "${app.vehicle-classes.sweep-every:5m}")
    @Transactional
    public int applyToNew() {
        int changed = 0;
        for (Vehicle v : vehicles.findByVehicleClassIsNullAndModelIsNotNull()) {
            if (apply(v, classifier.classify(v.getModel()))) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Vehicle classes applied to {} new vehicles", changed);
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
