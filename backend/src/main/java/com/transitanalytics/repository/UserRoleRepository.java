package com.transitanalytics.repository;

import com.transitanalytics.domain.UserRole;
import com.transitanalytics.service.CrudService;
import java.util.Optional;

public interface UserRoleRepository extends CrudService.Repo<UserRole> {
    Optional<UserRole> findByName(String name);
}
