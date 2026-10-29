package com.example.debt.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class IdentityApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired UserAccounts users;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetFictionalState() {
        jdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("UPDATE app_user SET enabled=true,role='ADMIN' WHERE id IN (?,?)")
                .params(AuthFixtures.ALPHA_ADMIN, AuthFixtures.BETA_ADMIN).update();
    }

    @AfterEach
    void restoreMembershipFixtures() {
        jdbc.sql("UPDATE app_user SET enabled=true,role='ADMIN' WHERE id IN (?,?)")
                .params(AuthFixtures.ALPHA_ADMIN, AuthFixtures.BETA_ADMIN).update();
    }

    @Test
    void rotatesSessionAndCsrfAndStoresOnlySafePrincipal() throws Exception {
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String anonymousId = session.getId();
        String anonymousToken = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", anonymousToken)
                        .contentType("application/json").content(loginBody(" ALPHA-ADMIN@example.test ", AuthFixtures.PASSWORD)))
                .andExpect(status().isNoContent());
        assertThat(session.getId()).isNotEqualTo(anonymousId);
        var authenticated = mvc.perform(get("/api/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("alpha-admin@example.test"))
                .andExpect(jsonPath("$.role").value("ADMIN")).andReturn();
        String newToken = json.readTree(authenticated.getResponse().getContentAsString()).get("csrfToken").asString();
        mvc.perform(post("/api/customers").session(session).header("X-CSRF-TOKEN", anonymousToken)
                        .contentType("application/json").content("{\"code\":\"old-token\",\"name\":\"rejected\"}"))
                .andExpect(status().isForbidden());
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(TenantUser.class);
        assertThat(context.getAuthentication().getPrincipal().toString()).doesNotContain(AuthFixtures.PASSWORD, "$argon2");
        mvc.perform(post("/api/logout").session(session).header("X-CSRF-TOKEN", newToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.sql("SELECT action FROM audit_event ORDER BY occurred_at").query(String.class).list())
                .containsExactly("SESSION_STARTED", "SESSION_ENDED");
        assertThat(jdbc.sql("SELECT details::text FROM audit_event").query(String.class).list())
                .allSatisfy(details -> assertThat(details).doesNotContain(AuthFixtures.PASSWORD, "$argon2", anonymousToken, newToken));
        assertThat(jdbc.sql("SELECT actor_user_id FROM audit_event").query(java.util.UUID.class).list())
                .containsOnly(AuthFixtures.ALPHA_ADMIN);
    }

    @Test
    void rejectsMissingCsrfWrongPasswordUnknownEmailAndBasicWithoutCreatingAudit() throws Exception {
        mvc.perform(get("/api/session").header("Authorization", "Basic YWxwaGEtYWRtaW46ZGVidF9sb2NhbA=="))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(loginBody("alpha-admin@example.test", AuthFixtures.PASSWORD)))
                .andExpect(status().isForbidden());
        var csrf = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        var wrong = mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(loginBody("alpha-admin@example.test", "incorrect password")))
                .andExpect(status().isUnauthorized()).andReturn();
        var missing = mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(loginBody("not-registered@example.test", "incorrect password")))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        mvc.perform(get("/api/session").session(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isZero();
    }

    @Test
    void reloadsMembershipFromDbAndInvalidatesDisabledUserSession() throws Exception {
        var session = AuthFixtures.login(mvc, "alpha-admin@example.test");
        jdbc.sql("UPDATE app_user SET role='ACCOUNTANT' WHERE id=?").param(AuthFixtures.ALPHA_ADMIN).update();
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ACCOUNTANT"));
        jdbc.sql("UPDATE app_user SET enabled=false WHERE id=?").param(AuthFixtures.ALPHA_ADMIN).update();
        mvc.perform(get("/api/customers").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void hashRemainsPersistedWithoutPlaintextAndEmailIsGloballyUnique() {
        var user = users.byEmail("alpha-admin@example.test").orElseThrow();
        assertThat(user.passwordHash()).startsWith("$argon2id$").doesNotContain(AuthFixtures.PASSWORD);
        assertThat(passwords.matches(AuthFixtures.PASSWORD, user.passwordHash())).isTrue();
        assertThat(user.toString()).doesNotContain(user.passwordHash(), AuthFixtures.PASSWORD);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,?,?)
                """).params(java.util.UUID.randomUUID(), AuthFixtures.BETA, user.email(), user.passwordHash(), "ADMIN").update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void rejectsUnknownPrivilegeFieldsAndNonStringPasswords() throws Exception {
        var csrf = mvc.perform(get("/api/csrf")).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        for (String body : java.util.List.of(
                "{\"email\":\"alpha-admin@example.test\",\"password\":42}",
                json.writeValueAsString(Map.of("email", "alpha-admin@example.test", "password", AuthFixtures.PASSWORD, "role", "ADMIN")))) {
            mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isZero();
    }

    @Test
    void localRestartDoesNotOverwriteExistingPassword() throws Exception {
        String originalHash = users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow().passwordHash();
        String differentPassword = "different_local_fixture_password";
        new LocalSeed().localUsers(jdbc, users, passwords, transactions, differentPassword).run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow().passwordHash()).isEqualTo(originalHash);
        assertThat(passwords.matches(differentPassword, originalHash)).isFalse();
    }

    @Test
    void crossTenantAuditActorConstraintRollsBackCustomerWrite() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                    java.util.UUID id = java.util.UUID.randomUUID();
                    jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,?)")
                            .params(id, AuthFixtures.BETA, "wrong-actor", "Must roll back").update();
                    jdbc.sql("""
                            INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                            VALUES (?,?,?,?,?,?, '{}'::jsonb)
                            """).params(java.util.UUID.randomUUID(), AuthFixtures.BETA, "user:" + AuthFixtures.ALPHA_ADMIN,
                                    AuthFixtures.ALPHA_ADMIN, "CUSTOMER_CREATED", id).update();
                })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isZero();
    }

    private String loginBody(String email, String password) {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }
}
