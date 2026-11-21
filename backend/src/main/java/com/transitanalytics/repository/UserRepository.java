package com.transitanalytics.repository;

import com.transitanalytics.domain.User;
import com.transitanalytics.service.CrudService;

import java.util.Optional;

public interface UserRepository extends CrudService.Repo<User> {

    Optional<User> findByUsername(String username);
}
