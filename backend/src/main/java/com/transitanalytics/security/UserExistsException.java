package com.transitanalytics.security;

/** A new user's name is taken. */
public class UserExistsException extends RuntimeException {

    public UserExistsException(String username) {
        super("User " + username + " already exists");
    }
}
