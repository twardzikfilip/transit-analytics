package com.transitanalytics.security;

import com.transitanalytics.domain.RoleName;

import java.util.Set;

/** What a new user's name, password and role must be; the Users page checks the same as you type. */
public final class UserRules {

    static final int NAME_MIN = 3;
    static final int NAME_MAX = 20;
    static final int PASSWORD_MIN = 10;
    /** Of lowercase, uppercase, digits and other characters, a password has at least this many kinds. */
    static final int PASSWORD_KINDS = 2;
    static final Set<String> ROLES = Set.of(RoleName.ADMIN, RoleName.GUEST);

    private UserRules() {
    }

    /** @throws IllegalArgumentException when the name, password or role breaks a rule */
    static void check(String username, String password, String role) {
        if (username == null || username.length() < NAME_MIN || username.length() > NAME_MAX) {
            throw new IllegalArgumentException("A user name has " + NAME_MIN + " to " + NAME_MAX + " characters");
        }
        if (username.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A user name has no spaces");
        }
        if (Character.isDigit(username.charAt(0))) {
            throw new IllegalArgumentException("A user name doesn't start with a digit");
        }
        if (password == null || password.length() < PASSWORD_MIN || kinds(password) < PASSWORD_KINDS) {
            throw new IllegalArgumentException("A password has at least " + PASSWORD_MIN + " characters, of at least "
                    + PASSWORD_KINDS + " kinds: lowercase, uppercase, digits, other characters");
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("A role is ADMIN or GUEST");
        }
    }

    /** How many kinds of character a password has: lowercase, uppercase, digits, others. */
    static int kinds(String password) {
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean other = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
    }
}
