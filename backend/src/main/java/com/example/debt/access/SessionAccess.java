package com.example.debt.access;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@Profile({"local", "auth-test"})
public class SessionAccess {
    @Bean
    SecurityContextRepository sessionContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    SecurityFilterChain sessionSecurity(HttpSecurity http, AccessErrors errors, UserAccounts accounts,
            CsrfTokenRepository csrf, SecurityContextRepository contexts) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/csrf", "/api/auth/login", "/api/invitations/inspect", "/api/invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/invitations").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(config -> config.csrfTokenRepository(csrf))
                .addFilterBefore(new MembershipFilter(accounts, errors), AuthorizationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, 401, "UNAUTHENTICATED", "もう一度ログインしてください。"))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, 403, exception instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                                        "操作権限またはセッションを確認してください。")))
                .build();
    }
}
