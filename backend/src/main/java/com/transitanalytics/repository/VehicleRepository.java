package com.transitanalytics.repository;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.service.CrudService;

import java.util.Collection;
import java.util.List;

public interface VehicleRepository extends CrudService.Repo<Vehicle> {

    List<Vehicle> findByVehicleNumberIn(Collection<String> vehicleNumbers);
}
