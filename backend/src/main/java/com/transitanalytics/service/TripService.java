package com.transitanalytics.service;

import com.transitanalytics.domain.Trip;
import com.transitanalytics.dto.TripDto;
import com.transitanalytics.repository.RouteRepository;
import com.transitanalytics.repository.TripRepository;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.stereotype.Service;

@Service
public class TripService extends CrudService<Trip, TripDto.Request, TripDto.Response> {

    private final RouteRepository routes;
    private final VehicleRepository vehicles;

    public TripService(TripRepository repository, RouteRepository routes, VehicleRepository vehicles) {
        super(repository, "Trip");
        this.routes = routes;
        this.vehicles = vehicles;
    }

    @Override
    protected Trip newEntity() {
        return new Trip();
    }

    @Override
    protected void apply(TripDto.Request r, Trip e, boolean creating) {
        e.setRoute(reference(routes, r.routeId(), "Route"));
        e.setVehicle(reference(vehicles, r.vehicleId(), "Vehicle"));
        e.setHeadsign(r.headsign());
        e.setDirection(r.direction());
        e.setStartTime(r.startTime());
        e.setEndTime(r.endTime());
    }

    @Override
    protected TripDto.Response toResponse(Trip e) {
        return new TripDto.Response(e.getId(), e.getRoute().getId(),
                e.getVehicle() != null ? e.getVehicle().getId() : null,
                e.getHeadsign(), e.getDirection(), e.getStartTime(), e.getEndTime());
    }
}
