package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.repository.RouteRepository;
import org.springframework.stereotype.Service;

@Service
public class RouteService extends CrudService<Route, RouteDto.Request, RouteDto.Response> {

    public RouteService(RouteRepository repository) {
        super(repository, "Route");
    }

    @Override
    protected Route newEntity() {
        return new Route();
    }

    @Override
    protected void apply(RouteDto.Request r, Route e, boolean creating) {
        e.setShortName(r.shortName());
        e.setLongName(r.longName());
        e.setType(r.type());
        e.setColor(r.color());
    }

    @Override
    protected RouteDto.Response toResponse(Route e) {
        return new RouteDto.Response(e.getId(), e.getShortName(), e.getLongName(), e.getType(), e.getColor());
    }
}
