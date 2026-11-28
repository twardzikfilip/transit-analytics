package com.transitanalytics.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductionSecretsTest {

    private final Users users = mock(Users.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    {
        user("admin", "admin");
        user("guest", "chosen-before");
    }

    private void user(String name, String password) {
        given(users.find(name)).willReturn(Optional.of(new Users.Account(name, encoder.encode(password), "ADMIN")));
    }

    private void start(String environment, String jwt, String db, String adminPassword) {
        new ProductionSecrets(users, encoder, environment, jwt, db, "r-secret", "g-secret", adminPassword,
                "a-new-guest-password").afterSingletonsInstantiated();
    }

    @Test
    void replacesOnlyPasswordsFromTheRepository() {
        start("production", "a-secret", "db-secret", "a-new-admin-password");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).setPasswordHash(eq("admin"), hash.capture());
        assertThat(encoder.matches("a-new-admin-password", hash.getValue())).isTrue();
        verify(users, never()).setPasswordHash(eq("guest"), any());
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
        verify(users, never()).setPasswordHash(any(), any());
    }
}
