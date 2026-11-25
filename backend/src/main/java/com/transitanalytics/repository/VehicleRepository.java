package com.transitanalytics.repository;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.service.CrudService;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VehicleRepository extends CrudService.Repo<Vehicle> {

    /**
     * Buses with a model but no size class, or an agency but no operating company: registered by transit-recorder
     * since the last sweep (or not covered by the rules).
     */
    @Query("""
            SELECT v FROM Vehicle v
            WHERE (v.vehicleClass IS NULL AND v.model IS NOT NULL)
               OR (v.operatingAgency IS NULL AND v.agency IS NOT NULL)""")
    List<Vehicle> findUnclassified();
}
