package com.transitanalytics.repository;

import com.transitanalytics.domain.Route;
import com.transitanalytics.service.CrudService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;

public interface RouteRepository extends CrudService.Repo<Route> {

    /** Loads the agency in the same query, so a page of routes doesn't trigger one query per row. */
    @Override
    @EntityGraph(attributePaths = "agency")
    Page<Route> findAll(Specification<Route> spec, Pageable pageable);
}
