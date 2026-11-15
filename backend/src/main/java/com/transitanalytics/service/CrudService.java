package com.transitanalytics.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic CRUD logic shared by all entity services.
 *
 * @param <E>   JPA entity type
 * @param <REQ> request DTO used for create/update
 * @param <RES> response DTO returned to clients
 */
public abstract class CrudService<E, REQ, RES> {

    protected final JpaRepository<E, Long> repository;
    private final String entityName;

    protected CrudService(JpaRepository<E, Long> repository, String entityName) {
        this.repository = repository;
        this.entityName = entityName;
    }

    protected abstract E newEntity();

    /** Copies request values onto the entity (used for both create and update). */
    protected abstract void apply(REQ request, E entity, boolean creating);

    protected abstract RES toResponse(E entity);

    @Transactional(readOnly = true)
    public Page<RES> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public RES findById(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional
    public RES create(REQ request) {
        E entity = newEntity();
        apply(request, entity, true);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public RES update(Long id, REQ request) {
        E entity = getEntity(id);
        apply(request, entity, false);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException(entityName, id);
        }
        repository.deleteById(id);
    }

    protected E getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException(entityName, id));
    }

    protected <T> T reference(JpaRepository<T, Long> repo, Long id, String name) {
        if (id == null) {
            return null;
        }
        return repo.findById(id).orElseThrow(() -> new NotFoundException(name, id));
    }
}
