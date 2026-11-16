package com.transitanalytics.repository;

import com.transitanalytics.domain.VehiclePosition;
import com.transitanalytics.service.CrudService;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VehiclePositionRepository extends CrudService.Repo<VehiclePosition> {

    /** Most recent position of every vehicle. */
    @Query("""
            select p from VehiclePosition p join fetch p.vehicle v
            where p.recordedAt = (
                select max(p2.recordedAt) from VehiclePosition p2 where p2.vehicle = p.vehicle
            )
            """)
    List<VehiclePosition> findLatestPerVehicle();
}
