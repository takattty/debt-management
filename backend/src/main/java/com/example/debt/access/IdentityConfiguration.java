package com.example.debt.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@Configuration
public class IdentityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }

    /** Suppress Spring's generated default account; all application authentication uses persisted users. */
    @Bean
    UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException("Session authentication is required");
        };
    }
}
