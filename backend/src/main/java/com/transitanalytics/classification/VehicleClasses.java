package com.transitanalytics.classification;

import com.transitanalytics.domain.VehicleClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Keeps every bus's class (and capacity, where known) in route with {@code classification/vehicles.yml}, and the company
 * running it with {@code classification/operators.yml}.
 */
@Service
public class VehicleClasses {

    private static final Logger log = LoggerFactory.getLogger(VehicleClasses.class);

    /** A bus as classed: its class and capacity from its model, its operating company from its agency and number. */
    public record Vehicle(long id, String number, String agency, String model, VehicleClass vehicleClass,
                          Integer capacity, String operatingAgency) {
    }

    private final JdbcTemplate jdbc;
    private final VehicleClassifier classifier;
    private final OperatorProperties operators;

    public VehicleClasses(JdbcTemplate jdbc, VehicleClassifier classifier, OperatorProperties operators) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.operators = operators;
    }

    /** Classes every bus at startup, before the routes are classified (their capacity counts the big buses). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void applyAll() {
        log.info("Vehicle classes and operators applied; {} vehicles changed", applyTo(""));
    }

    /**
     * Classes the buses transit-recorder registered since (it stores a new bus's number, type, agency and model only),
     * every few minutes: those with a model but no class, or an agency but no operating company.
     *
     * @return how many were classed
     */
    @Scheduled(fixedDelayString = "${app.vehicle-classes.sweep-every:5m}",
            initialDelayString = "${app.vehicle-classes.sweep-every:5m}")
    public int applyToNew() {
        int changed = applyTo("""
                WHERE (vehicle_class IS NULL AND model IS NOT NULL) OR (operating_agency IS NULL AND agency IS NOT NULL)""");
        if (changed > 0) {
            log.info("Vehicle classes and operators applied to {} new vehicles", changed);
        }
        return changed;
    }

    private int applyTo(String where) {
        List<Vehicle> vehicles = jdbc.query("""
                SELECT id, vehicle_number, agency, model, vehicle_class, capacity, operating_agency
                FROM vehicle\s""" + where, (rs, i) -> new Vehicle(rs.getLong("id"), rs.getString("vehicle_number"), rs.getString("agency"),
                rs.getString("model"), VehicleClass.of(rs.getString("vehicle_class")),
                (Integer) rs.getObject("capacity"), rs.getString("operating_agency")));
        int changed = 0;
        for (Vehicle v : vehicles) {
            Vehicle classed = classed(v);
            if (!classed.equals(v)) {
                jdbc.update("UPDATE vehicle SET vehicle_class = ?, capacity = ?, operating_agency = ? WHERE id = ?",
                        classed.vehicleClass() == null ? null : classed.vehicleClass().value(), classed.capacity(),
                        classed.operatingAgency(), v.id());
                changed++;
            }
        }
        return changed;
    }

    /** The bus with its class, capacity and operating company set. */
    Vehicle classed(Vehicle v) {
        Vehicle withClass = apply(v, classifier.classify(v.model()));
        return new Vehicle(v.id(), v.number(), v.agency(), v.model(), withClass.vehicleClass(), withClass.capacity(),
                operators.operatingAgency(v.agency(), v.number()));
    }

    /**
     * A bus's class from its model's spec, and its capacity when the spec has one (a capacity entered by hand is kept
     * otherwise).
     */
    public static Vehicle apply(Vehicle v, VehicleClassifier.Spec spec) {
        Integer capacity = spec.capacity() != null ? spec.capacity() : v.capacity();
        return new Vehicle(v.id(), v.number(), v.agency(), v.model(), spec.vehicleClass(), capacity,
                v.operatingAgency());
    }
}
