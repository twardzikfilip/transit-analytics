package com.transitanalytics.service;

import com.transitanalytics.domain.Agency;
import com.transitanalytics.dto.AgencyDto;
import com.transitanalytics.repository.AgencyRepository;
import org.springframework.stereotype.Service;

@Service
public class AgencyService extends CrudService<Agency, AgencyDto.Request, AgencyDto.Response, NoFilter> {

    public AgencyService(AgencyRepository repository) {
        super(repository, "Agency");
    }

    @Override
    protected Agency newEntity() {
        return new Agency();
    }

    @Override
    protected void apply(AgencyDto.Request r, Agency e, boolean creating) {
        e.setGtfsId(r.gtfsId());
        e.setName(r.name());
        e.setUrl(r.url());
        e.setTimezone(r.timezone());
        e.setLang(r.lang());
        e.setPhone(r.phone());
        e.setFareUrl(r.fareUrl());
        e.setEmail(r.email());
    }

    @Override
    protected AgencyDto.Response toResponse(Agency e) {
        return new AgencyDto.Response(e.getId(), e.getGtfsId(), e.getName(), e.getUrl(), e.getTimezone(), e.getLang(),
                e.getPhone(), e.getFareUrl(), e.getEmail());
    }
}
