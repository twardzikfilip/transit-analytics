package com.transitanalytics.service;

import com.transitanalytics.domain.Trip;
import com.transitanalytics.dto.TripDto;
import com.transitanalytics.repository.RouteRepository;
import com.transitanalytics.repository.TripRepository;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class TripService extends CrudService<Trip, TripDto.Request, TripDto.Response, TripDto.Filter> {

    private final RouteRepository routes;
    private final VehicleRepository vehicles;

    public TripService(TripRepository repository, RouteRepository routes, VehicleRepository vehicles) {
        super(repository, "Trip");
        this.routes = routes;
        this.vehicles = vehicles;
    }

    @Override
    protected Specification<Trip> toSpecification(TripDto.Filter f) {
        return Specs.all(Specs.idOf("route", f.routeId()), Specs.equal("serviceId", f.serviceId()),
                Specs.equal("direction", f.direction()), Specs.contains(f.q(), "headsign"));
    }

    @Override
    protected Trip newEntity() {
        return new Trip();
    }

    @Override
    protected void apply(TripDto.Request r, Trip e, boolean creating) {
        e.setGtfsId(r.gtfsId());
        e.setRoute(reference(routes, r.routeId(), "Route"));
        e.setVehicle(reference(vehicles, r.vehicleId(), "Vehicle"));
        e.setServiceId(r.serviceId());
        e.setHeadsign(r.headsign());
        e.setShortName(r.shortName());
        e.setDirection(r.direction());
        e.setBlockId(r.blockId());
        e.setShapeId(r.shapeId());
        e.setWheelchairAccessible(r.wheelchairAccessible());
        e.setBikesAllowed(r.bikesAllowed());
    }

    @Override
    protected TripDto.Response toResponse(Trip e) {
        var route = e.getRoute();
        // The vehicle's id is available without loading the vehicle.
        Long vehicleId = e.getVehicle() != null ? e.getVehicle().getId() : null;
        return new TripDto.Response(e.getId(), e.getGtfsId(), route.getId(), route.displayName(), route.getColor(),
                vehicleId, e.getServiceId(), e.getHeadsign(), e.getShortName(), e.getDirection(), e.getBlockId(),
                e.getShapeId(), e.getWheelchairAccessible(), e.getBikesAllowed());
    }
}
