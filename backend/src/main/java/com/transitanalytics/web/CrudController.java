package com.transitanalytics.web;

import com.transitanalytics.service.CrudService;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Standard REST CRUD endpoints; subclasses only add {@code @RequestMapping} and a service. */
public abstract class CrudController<REQ, RES> {

    protected final CrudService<?, REQ, RES> service;

    protected CrudController(CrudService<?, REQ, RES> service) {
        this.service = service;
    }

    @GetMapping
    public Page<RES> list(@ParameterObject @PageableDefault(size = 50, sort = "id") Pageable pageable) {
        return service.findAll(pageable);
    }

    @GetMapping("/{id}")
    public RES get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RES create(@Valid @RequestBody REQ request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public RES update(@PathVariable Long id, @Valid @RequestBody REQ request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
