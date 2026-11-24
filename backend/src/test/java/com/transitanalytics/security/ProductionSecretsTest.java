package com.transitanalytics.security;

import com.transitanalytics.domain.User;
import com.transitanalytics.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductionSecretsTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final User admin = user("admin", "admin");
    private final User guest = user("guest", "chosen-before");

    private User user(String name, String password) {
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(encoder.encode(password));
        given(users.findByUsername(name)).willReturn(Optional.of(user));
        return user;
    }

    private void start(String environment, String jwt, String db, String adminPassword) {
        new ProductionSecrets(users, encoder, environment, jwt, db, "r-secret", "g-secret", adminPassword,
                "a-new-guest-password").afterSingletonsInstantiated();
    }

    @Test
    void replacesOnlyPasswordsFromTheRepository() {
        start("production", "a-secret", "db-secret", "a-new-admin-password");

        assertThat(encoder.matches("a-new-admin-password", admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches("chosen-before", guest.getPasswordHash())).as("changed since: stays").isTrue();
        verify(users).save(admin);
        verify(users, never()).save(guest);
    }

    @Test
    void doesntStartWithWhatIsInTheRepository() {
        assertThatThrownBy(() -> start("production", "dev-only-secret-change-me-please-0123456789abcdef", "db-secret",
                "a-new-admin-password")).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> start("production", "a-secret", "transit", "a-new-admin-password"))
                .hasMessageContaining("DB_PASSWORD");
        assertThatThrownBy(() -> start("production", "a-secret", "", "a-new-admin-password"))
                .hasMessageContaining("DB_PASSWORD");
        assertThatThrownBy(() -> start("production", "a-secret", "db-secret", "short"))
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void changesNothingElsewhere() {
        start("local", "", "transit", "");
        verify(users, never()).save(any());
    }
}
