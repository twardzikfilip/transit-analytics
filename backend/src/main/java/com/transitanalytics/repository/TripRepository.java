package com.transitanalytics.repository;

import com.transitanalytics.domain.Trip;
import com.transitanalytics.service.CrudService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

public interface TripRepository extends CrudService.Repo<Trip> {

    @Override
    @EntityGraph(attributePaths = "route")
    Page<Trip> findAll(Specification<Trip> spec, Pageable pageable);
}
