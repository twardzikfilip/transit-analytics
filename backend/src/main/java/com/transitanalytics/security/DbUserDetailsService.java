package com.transitanalytics.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DbUserDetailsService implements UserDetailsService {

    private final Users users;

    public DbUserDetailsService(Users users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.find(username)
                .map(u -> User.withUsername(u.username())
                        .password(u.passwordHash())
                        .roles(u.role())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
