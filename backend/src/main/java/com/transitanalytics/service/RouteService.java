package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.repository.AgencyRepository;
import com.transitanalytics.repository.RouteRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class RouteService extends CrudService<Route, RouteDto.Request, RouteDto.Response, RouteDto.Filter> {

    private final AgencyRepository agencies;

    public RouteService(RouteRepository repository, AgencyRepository agencies) {
        super(repository, "Route");
        this.agencies = agencies;
    }

    @Override
    protected Specification<Route> toSpecification(RouteDto.Filter f) {
        return Specs.all(Specs.contains(f.q(), "shortName", "longName"), Specs.equal("routeType", f.routeType()),
                Specs.equal("subtype", f.subtype()));
    }

    @Override
    protected Route newEntity() {
        return new Route();
    }

    @Override
    protected void apply(RouteDto.Request r, Route e, boolean creating) {
        e.setGtfsId(r.gtfsId());
        e.setAgency(reference(agencies, r.agencyId(), "Agency"));
        e.setShortName(r.shortName());
        e.setLongName(r.longName());
        e.setDescription(r.description());
        e.setRouteType(r.routeType());
        e.setUrl(r.url());
        e.setColor(r.color());
        e.setTextColor(r.textColor());
        e.setSortOrder(r.sortOrder());
        e.setSubtype(r.subtype() != null ? r.subtype() : RouteSubtype.classify(e.displayName()));
    }

    @Override
    protected RouteDto.Response toResponse(Route e) {
        var agency = e.getAgency();
        return new RouteDto.Response(e.getId(), e.getGtfsId(), agency != null ? agency.getId() : null,
                agency != null ? agency.getName() : null, e.displayName(), e.getShortName(), e.getLongName(),
                e.getDescription(), e.getRouteType(), e.getUrl(), e.getColor(), e.getTextColor(), e.getSortOrder(),
                e.getSubtype(), e.getGeometry(), e.getCapacityClass(), e.getOperatingAgency());
    }
}
