package com.transitanalytics.service;

import com.transitanalytics.domain.Trip;
import com.transitanalytics.dto.TripDto;
import com.transitanalytics.repository.RouteRepository;
import com.transitanalytics.repository.TripRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class TripService extends CrudService<Trip, TripDto.Request, TripDto.Response, TripDto.Filter> {

    private final RouteRepository routes;

    public TripService(TripRepository repository, RouteRepository routes) {
        super(repository, "Trip");
        this.routes = routes;
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
        return new TripDto.Response(e.getId(), e.getGtfsId(), route.getId(), route.displayName(), route.getColor(),
                e.getServiceId(), e.getHeadsign(), e.getShortName(), e.getDirection(), e.getBlockId(),
                e.getShapeId(), e.getWheelchairAccessible(), e.getBikesAllowed());
    }
}
