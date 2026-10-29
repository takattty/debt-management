package com.example.debt.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Fail closed until real membership and authentication are implemented. */
@Configuration
@Profile("!dev")
public class ClosedAccess {
    @Bean
    SecurityFilterChain closedSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll()).build();
    }
}
