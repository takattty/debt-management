package com.example.debt.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class LoginRateLimitApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean(name = "loginRateLimitClock") Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final java.util.concurrent.atomic.AtomicInteger WINDOWS = new java.util.concurrent.atomic.AtomicInteger();
    private Instant start;

    @BeforeEach
    void freshClockWindow() {
        start = Instant.parse("2026-10-29T00:00:00Z").plusSeconds(WINDOWS.incrementAndGet() * 120L);
        when(clock.instant()).thenReturn(start);
    }

    @Test
    void freshCookiesAndForwardedAddressesCannotResetBudgetAndClockExpiryRecovers() throws Exception {
        String email = "limit-" + UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 10; i++) { attempt(email, "unregistered fictional password", i).andExpect(status().isUnauthorized()); }
        var limited = attempt(email, "unregistered fictional password", 10).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andReturn();
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        attempt(email, "unregistered fictional password", 11).andExpect(status().isUnauthorized());
        for (int i = 0; i < 9; i++) { attempt("alpha-admin@example.test", "wrong fictional password", i).andExpect(status().isUnauthorized()); }
        attempt("alpha-admin@example.test", AuthFixtures.PASSWORD, 0).andExpect(status().isNoContent());
        attempt("alpha-admin@example.test", "wrong fictional password", 0).andExpect(status().isUnauthorized());
    }

    @Test
    void successfulLoginsCannotResetRemoteAddressLimitOrSpoofItWithForwardedHeaders() throws Exception {
        for (int i = 0; i < 60; i++) {
            attempt("alpha-admin@example.test", AuthFixtures.PASSWORD, i).andExpect(status().isNoContent());
        }
        attempt("alpha-admin@example.test", AuthFixtures.PASSWORD, 61).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private org.springframework.test.web.servlet.ResultActions attempt(String email, String password, int alias) throws Exception {
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        return mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .header("X-Forwarded-For", "192.0.2." + alias).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }
}
