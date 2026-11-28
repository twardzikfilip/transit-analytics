package com.transitanalytics.security;

import com.transitanalytics.DbIntegrationTest;
import com.transitanalytics.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Users page's store: list, add (with the rules) and remove. */
@DbIntegrationTest
@Transactional
class UsersIntegrationTest {

    @Autowired
    Users users;

    @Autowired
    UserController controller;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void addsListsAndRemovesUsers() {
        UserDto.User ann = controller.create(new UserDto.NewUser("it-ann", "Secret-pass1", "GUEST"));

        assertThat(ann.role()).isEqualTo("GUEST");
        assertThat(users.list()).extracting(UserDto.User::username).contains("admin", "guest", "it-ann");
        assertThat(encoder.matches("Secret-pass1", users.find("it-ann").orElseThrow().passwordHash())).isTrue();
        // Names are one name in any case.
        assertThatThrownBy(() -> controller.create(new UserDto.NewUser("IT-Ann", "Secret-pass1", "ADMIN")))
                .isInstanceOf(UserExistsException.class);

        assertThat(users.delete("it-ann")).isTrue();
        assertThat(users.find("it-ann")).isEmpty();
        assertThat(users.delete("it-ann")).isFalse();
    }

    @Test
    void rulesForNamesAndPasswords() {
        assertThat(UserRules.kinds("abcdefghij")).isEqualTo(1);
        assertThat(UserRules.kinds("abcdefghi1")).isEqualTo(2);
        assertThat(UserRules.kinds("Ab1!")).isEqualTo(4);
        for (String[] bad : new String[][] {{"an", "Secret-pass1"}, {"a".repeat(21), "Secret-pass1"},
                {"an n", "Secret-pass1"}, {"1ann", "Secret-pass1"}, {"ann", "Short1!"}, {"ann", "lowercaseonly"}}) {
            assertThatThrownBy(() -> UserRules.check(bad[0], bad[1], "GUEST"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        UserRules.check("ann_2", "lowercase12", "ADMIN");
    }
}
