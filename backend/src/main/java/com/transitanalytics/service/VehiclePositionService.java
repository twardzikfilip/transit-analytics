package com.transitanalytics.service;

import com.transitanalytics.domain.VehiclePosition;
import com.transitanalytics.dto.VehiclePositionDto;
import com.transitanalytics.repository.TripRepository;
import com.transitanalytics.repository.VehiclePositionRepository;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class VehiclePositionService
        extends CrudService<VehiclePosition, VehiclePositionDto.Request, VehiclePositionDto.Response, NoFilter> {

    private final VehiclePositionRepository positions;
    private final VehicleRepository vehicles;
    private final TripRepository trips;

    public VehiclePositionService(VehiclePositionRepository repository, VehicleRepository vehicles,
                                  TripRepository trips) {
        super(repository, "VehiclePosition");
        this.positions = repository;
        this.vehicles = vehicles;
        this.trips = trips;
    }

    @Transactional(readOnly = true)
    public List<VehiclePositionDto.Response> findLatestPerVehicle() {
        return positions.findLatestPerVehicle().stream().map(this::toResponse).toList();
    }

    @Override
    protected VehiclePosition newEntity() {
        return new VehiclePosition();
    }

    @Override
    protected void apply(VehiclePositionDto.Request r, VehiclePosition e, boolean creating) {
        e.setVehicle(reference(vehicles, r.vehicleId(), "Vehicle"));
        e.setTrip(reference(trips, r.tripId(), "Trip"));
        e.setLatitude(r.latitude());
        e.setLongitude(r.longitude());
        e.setBearing(r.bearing());
        e.setSpeed(r.speed());
        e.setRecordedAt(r.recordedAt() != null ? r.recordedAt() : Instant.now());
    }

    @Override
    protected VehiclePositionDto.Response toResponse(VehiclePosition e) {
        return new VehiclePositionDto.Response(e.getId(), e.getVehicle().getId(), e.getVehicle().getVehicleNumber(),
                e.getTrip() != null ? e.getTrip().getId() : null,
                e.getLatitude(), e.getLongitude(), e.getBearing(), e.getSpeed(), e.getRecordedAt());
    }
}
