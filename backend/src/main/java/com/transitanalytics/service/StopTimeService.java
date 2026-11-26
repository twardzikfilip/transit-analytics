package com.transitanalytics.service;

import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.domain.StopTime;
import com.transitanalytics.dto.StopTimeDto;
import com.transitanalytics.repository.StopRepository;
import com.transitanalytics.repository.StopTimeRepository;
import com.transitanalytics.repository.TripRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class StopTimeService
        extends CrudService<StopTime, StopTimeDto.Request, StopTimeDto.Response, StopTimeDto.Filter> {

    private final TripRepository trips;
    private final StopRepository stops;

    public StopTimeService(StopTimeRepository repository, TripRepository trips, StopRepository stops) {
        super(repository, "StopTime");
        this.trips = trips;
        this.stops = stops;
    }

    @Override
    protected Specification<StopTime> toSpecification(StopTimeDto.Filter f) {
        return Specs.all(Specs.notEqual("trip.feed", TimetableFeed.RAIL),
                Specs.idOf("trip", f.tripId()), Specs.idOf("stop", f.stopId()));
    }

    @Override
    protected StopTime newEntity() {
        return new StopTime();
    }

    @Override
    protected void apply(StopTimeDto.Request r, StopTime e, boolean creating) {
        e.setTrip(reference(trips, r.tripId(), "Trip"));
        e.setStop(reference(stops, r.stopId(), "Stop"));
        e.setStopSequence(r.stopSequence());
        e.setArrivalTime(r.arrivalTime());
        e.setDepartureTime(r.departureTime());
        e.setStopHeadsign(r.stopHeadsign());
        e.setPickupType(r.pickupType());
        e.setDropOffType(r.dropOffType());
        e.setShapeDistTraveled(r.shapeDistTraveled());
        e.setTimepoint(r.timepoint());
    }

    @Override
    protected StopTimeDto.Response toResponse(StopTime e) {
        var trip = e.getTrip();
        var route = trip.getRoute();
        var stop = e.getStop();
        return new StopTimeDto.Response(e.getId(), trip.getId(), trip.getGtfsId(), trip.getHeadsign(),
                trip.getServiceId(), route.getId(),
                route.displayName(), route.getColor(), stop.getId(), stop.getCode(), stop.getName(),
                e.getStopSequence(), e.getArrivalTime(), e.getDepartureTime(), e.getStopHeadsign(),
                e.getPickupType(), e.getDropOffType(), e.getShapeDistTraveled(), e.getTimepoint());
    }
}
