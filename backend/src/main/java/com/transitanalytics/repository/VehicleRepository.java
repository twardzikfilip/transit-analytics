package com.transitanalytics.repository;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.service.CrudService;

import java.util.List;

public interface VehicleRepository extends CrudService.Repo<Vehicle> {

    /** Buses with a model but no size class yet: registered by transit-recorder since the last sweep. */
    List<Vehicle> findByVehicleClassIsNullAndModelIsNotNull();
}
