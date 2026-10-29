package com.example.debt.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@TestConfiguration
public class AuthFixtures {
    public static final UUID ALPHA = LocalSeed.ALPHA;
    public static final UUID BETA = LocalSeed.BETA;
    public static final UUID ALPHA_ADMIN = LocalSeed.ALPHA_ADMIN;
    public static final UUID BETA_ADMIN = LocalSeed.BETA_ADMIN;
    public static final UUID ALPHA_ACCOUNTANT = LocalSeed.ALPHA_ACCOUNTANT;
    public static final String PASSWORD = "debt_local_demo_2026";

    @Bean
    ApplicationRunner testUserFixtures(JdbcClient jdbc, PasswordEncoder passwords) {
        return args -> {
            jdbc.sql("INSERT INTO tenant(id,name) VALUES (?,?) ON CONFLICT(id) DO NOTHING")
                    .params(ALPHA, "Fictional Alpha").update();
            jdbc.sql("INSERT INTO tenant(id,name) VALUES (?,?) ON CONFLICT(id) DO NOTHING")
                    .params(BETA, "Fictional Beta").update();
            String hash = passwords.encode(PASSWORD);
            fixture(jdbc, ALPHA_ADMIN, ALPHA, "alpha-admin@example.test", hash, "ADMIN");
            fixture(jdbc, BETA_ADMIN, BETA, "beta-admin@example.test", hash, "ADMIN");
            fixture(jdbc, ALPHA_ACCOUNTANT, ALPHA, "alpha-accountant@example.test", hash, "ACCOUNTANT");
        };
    }

    private void fixture(JdbcClient jdbc, UUID id, UUID tenant, String email, String hash, String role) {
        jdbc.sql("""
                INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET password_hash=excluded.password_hash,enabled=true,role=excluded.role
                """).params(id, tenant, email, hash, role).update();
    }

    public static MockHttpSession login(MockMvc mvc, String email) throws Exception {
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        var json = JsonMapper.builder().build();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isNoContent());
        return session;
    }
}
