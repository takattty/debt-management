package com.example.debt.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Fail closed until real membership and authentication are implemented. */
@Configuration
@Profile("!local & !auth-test")
public class ClosedAccess {
    @Bean
    SecurityFilterChain closedSecurity(HttpSecurity http, AccessErrors errors) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, 403, "ACCESS_DENIED", "この環境では API を利用できません。"))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, 403, "ACCESS_DENIED", "この環境では API を利用できません。")))
                .build();
    }
}
