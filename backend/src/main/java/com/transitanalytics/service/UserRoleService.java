package com.transitanalytics.service;

import com.transitanalytics.domain.UserRole;
import com.transitanalytics.dto.UserRoleDto;
import com.transitanalytics.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

@Service
public class UserRoleService extends CrudService<UserRole, UserRoleDto.Request, UserRoleDto.Response> {

    public UserRoleService(UserRoleRepository repository) {
        super(repository, "UserRole");
    }

    @Override
    protected UserRole newEntity() {
        return new UserRole();
    }

    @Override
    protected void apply(UserRoleDto.Request r, UserRole e, boolean creating) {
        e.setName(r.name());
    }

    @Override
    protected UserRoleDto.Response toResponse(UserRole e) {
        return new UserRoleDto.Response(e.getId(), e.getName());
    }
}
