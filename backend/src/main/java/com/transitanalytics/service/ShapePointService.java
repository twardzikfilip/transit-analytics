package com.transitanalytics.service;

import com.transitanalytics.domain.ShapePoint;
import com.transitanalytics.dto.ShapePointDto;
import com.transitanalytics.repository.ShapePointRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class ShapePointService
        extends CrudService<ShapePoint, ShapePointDto.Request, ShapePointDto.Response, ShapePointDto.Filter> {

    public ShapePointService(ShapePointRepository repository) {
        super(repository, "ShapePoint");
    }

    @Override
    protected Specification<ShapePoint> toSpecification(ShapePointDto.Filter f) {
        return Specs.all(Specs.equal("shapeId", f.shapeId()));
    }

    @Override
    protected ShapePoint newEntity() {
        return new ShapePoint();
    }

    @Override
    protected void apply(ShapePointDto.Request r, ShapePoint e, boolean creating) {
        e.setShapeId(r.shapeId());
        e.setSequence(r.sequence());
        e.setLatitude(r.latitude());
        e.setLongitude(r.longitude());
        e.setShapeDistTraveled(r.shapeDistTraveled());
    }

    @Override
    protected ShapePointDto.Response toResponse(ShapePoint e) {
        return new ShapePointDto.Response(e.getId(), e.getShapeId(), e.getSequence(), e.getLatitude(),
                e.getLongitude(), e.getShapeDistTraveled());
    }
}
