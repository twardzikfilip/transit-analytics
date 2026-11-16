package com.transitanalytics.service;

import com.transitanalytics.domain.CalendarDate;
import com.transitanalytics.dto.CalendarDateDto;
import com.transitanalytics.repository.CalendarDateRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class CalendarDateService extends
        CrudService<CalendarDate, CalendarDateDto.Request, CalendarDateDto.Response, CalendarDateDto.Filter> {

    public CalendarDateService(CalendarDateRepository repository) {
        super(repository, "CalendarDate");
    }

    @Override
    protected Specification<CalendarDate> toSpecification(CalendarDateDto.Filter f) {
        return Specs.all(Specs.equal("serviceId", f.serviceId()));
    }

    @Override
    protected CalendarDate newEntity() {
        return new CalendarDate();
    }

    @Override
    protected void apply(CalendarDateDto.Request r, CalendarDate e, boolean creating) {
        e.setServiceId(r.serviceId());
        e.setDate(r.date());
        e.setExceptionType(r.exceptionType());
    }

    @Override
    protected CalendarDateDto.Response toResponse(CalendarDate e) {
        return new CalendarDateDto.Response(e.getId(), e.getServiceId(), e.getDate(), e.getExceptionType());
    }
}
