package com.transitanalytics.service;

import com.transitanalytics.domain.Stop;
import com.transitanalytics.dto.StopDto;
import com.transitanalytics.repository.StopRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class StopService extends CrudService<Stop, StopDto.Request, StopDto.Response, StopDto.Filter> {

    public StopService(StopRepository repository) {
        super(repository, "Stop");
    }

    @Override
    protected Specification<Stop> toSpecification(StopDto.Filter f) {
        return Specs.all(Specs.contains(f.q(), "name", "code"), Specs.equal("zoneId", f.zoneId()));
    }

    @Override
    protected Stop newEntity() {
        return new Stop();
    }

    @Override
    protected void apply(StopDto.Request r, Stop e, boolean creating) {
        e.setGtfsId(r.gtfsId());
        e.setCode(r.code());
        e.setName(r.name());
        e.setDescription(r.description());
        e.setLatitude(r.latitude());
        e.setLongitude(r.longitude());
        e.setZoneId(r.zoneId());
        e.setUrl(r.url());
        e.setLocationType(r.locationType());
        e.setParentStation(r.parentStation());
        e.setWheelchairBoarding(r.wheelchairBoarding());
        e.setPlatformCode(r.platformCode());
    }

    @Override
    protected StopDto.Response toResponse(Stop e) {
        return new StopDto.Response(e.getId(), e.getFeed(), e.getGtfsId(), e.getCode(), e.getName(), e.getDescription(),
                e.getLatitude(), e.getLongitude(), e.getZoneId(), e.getUrl(), e.getLocationType(),
                e.getParentStation(), e.getWheelchairBoarding(), e.getPlatformCode());
    }
}
