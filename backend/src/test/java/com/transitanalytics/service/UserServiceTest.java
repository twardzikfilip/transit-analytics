package com.transitanalytics.service;

import com.transitanalytics.domain.User;
import com.transitanalytics.domain.UserRole;
import com.transitanalytics.dto.UserDto;
import com.transitanalytics.repository.UserRepository;
import com.transitanalytics.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** User administration: passwords are stored hashed, roles must exist, and CRUD errors are reported by name. */
class UserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserService service = new UserService(users, roles, encoder);

    private static UserRole role(String name) {
        UserRole role = new UserRole();
        role.setName(name);
        return role;
    }

    @BeforeEach
    void setUp() {
        given(roles.findByName("GUEST")).willReturn(Optional.of(role("GUEST")));
        given(encoder.encode("secret")).willReturn("hashed");
        given(users.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAUserWithAHashedPassword() {
        UserDto.Response created = service.create(new UserDto.Request("ann", "secret", "GUEST"));

        assertThat(created.username()).isEqualTo("ann");
        assertThat(created.role()).isEqualTo("GUEST");
        verify(users).save(org.mockito.ArgumentMatchers.argThat(u -> "hashed".equals(u.getPasswordHash())));
    }

    @Test
    void aNewUserNeedsAPasswordAndAKnownRole() {
        assertThatThrownBy(() -> service.create(new UserDto.Request("ann", " ", "GUEST")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> service.create(new UserDto.Request("ann", "secret", "PILOT")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown role: PILOT");
        verify(users, never()).save(any());
    }

    @Test
    void updatingWithoutAPasswordKeepsTheOldOne() {
        User existing = new User();
        existing.setUsername("ann");
        existing.setPasswordHash("old-hash");
        existing.setRole(role("GUEST"));
        given(users.findById(7L)).willReturn(Optional.of(existing));

        service.update(7L, new UserDto.Request("anna", null, "GUEST"));

        assertThat(existing.getUsername()).isEqualTo("anna");
        assertThat(existing.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void missingUsersAreReportedAsNotFound() {
        given(users.findById(9L)).willReturn(Optional.empty());
        given(users.existsById(9L)).willReturn(false);

        assertThatThrownBy(() -> service.findById(9L)).isInstanceOf(NotFoundException.class)
                .hasMessage("User with id 9 not found");
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(NotFoundException.class);
        verify(users, never()).deleteById(any());
    }
}
