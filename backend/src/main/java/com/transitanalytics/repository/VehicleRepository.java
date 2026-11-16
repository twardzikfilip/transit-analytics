package com.transitanalytics.repository;

import com.transitanalytics.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByVehicleNumberIn(Collection<String> vehicleNumbers);
}
