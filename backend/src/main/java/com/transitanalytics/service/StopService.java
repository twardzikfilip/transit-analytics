package com.transitanalytics.service;

import com.transitanalytics.domain.Stop;
import com.transitanalytics.dto.StopDto;
import com.transitanalytics.repository.StopRepository;
import org.springframework.stereotype.Service;

@Service
public class StopService extends CrudService<Stop, StopDto.Request, StopDto.Response> {

    public StopService(StopRepository repository) {
        super(repository, "Stop");
    }

    @Override
    protected Stop newEntity() {
        return new Stop();
    }

    @Override
    protected void apply(StopDto.Request r, Stop e, boolean creating) {
        e.setCode(r.code());
        e.setName(r.name());
        e.setLatitude(r.latitude());
        e.setLongitude(r.longitude());
    }

    @Override
    protected StopDto.Response toResponse(Stop e) {
        return new StopDto.Response(e.getId(), e.getCode(), e.getName(), e.getLatitude(), e.getLongitude());
    }
}
