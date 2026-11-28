package com.transitanalytics.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * With {@code ENVIRONMENT=production}, nothing from the (public) repository lets anyone in: the app doesn't start with
 * the default secret or database passwords, and the seeded users (admin/admin, guest/guest) get the passwords
 * given in {@code ADMIN_PASSWORD} and {@code GUEST_PASSWORD}. Checked before the app takes requests.
 */
@Component
public class ProductionSecrets implements SmartInitializingSingleton {

    static final int MIN_PASSWORD = 10;
    private static final Logger log = LoggerFactory.getLogger(ProductionSecrets.class);

    private final Users users;
    private final PasswordEncoder encoder;
    private final boolean production;
    /** By setting: its value and the value in the repository. */
    private final Map<String, String[]> secrets = new LinkedHashMap<>();
    /** By seeded user (the password is the name): the password to give it. */
    private final Map<String, String> passwords = new LinkedHashMap<>();

    public ProductionSecrets(Users users, PasswordEncoder encoder,
                             @Value("${app.environment:local}") String environment,
                             @Value("${app.jwt.secret:}") String jwtSecret,
                             @Value("${spring.datasource.password:}") String databasePassword,
                             @Value("${spring.flyway.placeholders.recorder_db_password:}") String recorderPassword,
                             @Value("${spring.flyway.placeholders.grafana_db_password:}") String grafanaPassword,
                             @Value("${app.seed-passwords.admin:}") String admin,
                             @Value("${app.seed-passwords.guest:}") String guest) {
        this.users = users;
        this.encoder = encoder;
        this.production = "production".equals(environment);
        secrets.put("JWT_SECRET", new String[] {jwtSecret, "dev-only-secret-change-me-please-0123456789abcdef"});
        secrets.put("DB_PASSWORD", new String[] {databasePassword, "transit"});
        secrets.put("RECORDER_DB_PASSWORD", new String[] {recorderPassword, "transit_recorder"});
        secrets.put("GRAFANA_DB_PASSWORD", new String[] {grafanaPassword, "grafana_reader"});
        passwords.put("admin", admin);
        passwords.put("guest", guest);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!production) {
            return;
        }
        secrets.forEach((setting, values) -> {
            if (values[0].isBlank() || values[0].equals(values[1])) {
                throw new IllegalStateException(setting + " is missing or the one in the repository: set another");
            }
        });
        passwords.forEach((name, password) -> users.find(name).ifPresent(u -> replaceSeeded(u, password)));
    }

    private void replaceSeeded(Users.Account user, String password) {
        String name = user.username();
        if (!encoder.matches(name, user.passwordHash())) {
            return;
        }
        if (password.length() < MIN_PASSWORD) {
            throw new IllegalStateException("User " + name + " has the password from the repository: set "
                    + name.toUpperCase() + "_PASSWORD (at least " + MIN_PASSWORD + " characters)");
        }
        users.setPasswordHash(name, encoder.encode(password));
        log.info("User {} has its own password now", name);
    }
}
