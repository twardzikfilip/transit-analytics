package com.transitanalytics.service;

import com.transitanalytics.domain.User;
import com.transitanalytics.dto.UserDto;
import com.transitanalytics.repository.UserRepository;
import com.transitanalytics.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService extends CrudService<User, UserDto.Request, UserDto.Response> {

    private final UserRoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, UserRoleRepository roles, PasswordEncoder passwordEncoder) {
        super(repository, "User");
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    protected User newEntity() {
        return new User();
    }

    @Override
    protected void apply(UserDto.Request r, User e, boolean creating) {
        boolean hasPassword = r.password() != null && !r.password().isBlank();
        if (creating && !hasPassword) {
            throw new IllegalArgumentException("password is required when creating a user");
        }
        e.setUsername(r.username());
        if (hasPassword) {
            e.setPasswordHash(passwordEncoder.encode(r.password()));
        }
        e.setRole(roles.findByName(r.role())
                .orElseThrow(() -> new IllegalArgumentException("unknown role: " + r.role())));
    }

    @Override
    protected UserDto.Response toResponse(User e) {
        return new UserDto.Response(e.getId(), e.getUsername(), e.getRole().getName(), e.getCreatedAt());
    }
}
