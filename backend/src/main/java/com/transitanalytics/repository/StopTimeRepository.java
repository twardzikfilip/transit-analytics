package com.transitanalytics.repository;

import com.transitanalytics.domain.StopTime;
import com.transitanalytics.service.CrudService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

public interface StopTimeRepository extends CrudService.Repo<StopTime> {

    @Override
    @EntityGraph(attributePaths = {"trip", "trip.route", "stop"})
    Page<StopTime> findAll(Specification<StopTime> spec, Pageable pageable);
}
