package com.example.debt.access;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/** Fictional accounts are available only when the explicit dev profile is active. */
@Configuration
@Profile("dev")
public class DevelopmentAccess {
    public static final UUID ALPHA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID BETA = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Bean
    UserDetailsService developmentUsers(@Value("${DEV_PASSWORD:debt_local}") String password) {
        String encoded = new BCryptPasswordEncoder().encode(password);
        Map<String, UUID> users = Map.of("alpha-admin", ALPHA, "beta-admin", BETA);
        return username -> {
            UUID tenant = users.get(username);
            if (tenant == null) {
                throw new UsernameNotFoundException("Unknown development user");
            }
            return new TenantUser(username, encoded, tenant);
        };
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain developmentSecurity(HttpSecurity http, AccessErrors errors) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) ->
                        errors.write(response, 401, "UNAUTHENTICATED", "ログイン情報を確認してください。")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, 401, "UNAUTHENTICATED", "ログイン情報を確認してください。"))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, 403, "ACCESS_DENIED", "操作権限またはセッションを確認してください。")))
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    ApplicationRunner seedDevelopmentTenants(JdbcClient jdbc) {
        return args -> {
            jdbc.sql("INSERT INTO tenant(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")
                    .params(ALPHA, "Fictional Alpha").update();
            jdbc.sql("INSERT INTO tenant(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")
                    .params(BETA, "Fictional Beta").update();
        };
    }
}
