package com.example.debt.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import org.springframework.context.annotation.Import;
import com.example.debt.generated.model.ApiError;
import com.example.debt.generated.model.CustomerResponse;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class CustomerApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CustomerService service;
    @Autowired Validator validator;
    private MockHttpSession alphaSession;
    private MockHttpSession betaSession;

    @BeforeEach
    void clearFictionalData() throws Exception {
        jdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM customer").update();
        alphaSession = AuthFixtures.login(mvc, "alpha-admin@example.test");
        betaSession = AuthFixtures.login(mvc, "beta-admin@example.test");
    }

    @Test
    void persistsCustomerAndAuditAndSeparatesTenants() throws Exception {
        mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空商事\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/customers").session(alphaSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("架空商事"));
        mvc.perform(get("/api/customers").session(betaSession)
                        .header("X-Tenant-Id", AuthFixtures.ALPHA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'CUSTOMER_CREATED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidAndDuplicateRowsWithoutCreatingAdditionalAudit() throws Exception {
        mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                        .contentType("application/json").content("{\"code\":\"\",\"name\":\"架空\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"));
        CustomerValues values = new CustomerValues("C001", "架空");
        service.create(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, values);
        mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"別名\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER_CODE"));
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(1);
        service.create(AuthFixtures.BETA, AuthFixtures.BETA_ADMIN, values);
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void refusesUnauthenticatedAndMissingCsrfRequests() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/api/customers").session(alphaSession)
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(get("/api/session").session(alphaSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void issuedCsrfTokenAuthorizesMutationAndLogoutInvalidatesSession() throws Exception {
        var login = mvc.perform(get("/api/session").session(alphaSession))
                .andExpect(status().isOk()).andReturn();
        var token = JsonMapper.builder().build().readTree(login.getResponse().getContentAsString());
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(post("/api/customers").session(alphaSession)
                        .session(session).header(token.get("csrfHeaderName").asText(), token.get("csrfToken").asText())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/logout").session(alphaSession)
                        .session(session).header(token.get("csrfHeaderName").asText(), token.get("csrfToken").asText()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void preventsCrossTenantUpdateAndStaleVersion() throws Exception {
        Customer customer = service.create(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, new CustomerValues("C001", "架空"));
        String body = "{\"code\":\"C001\",\"name\":\"改訂\",\"version\":0}";
        mvc.perform(put("/api/customers/" + customer.id()).session(betaSession)
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/customers/" + customer.id()).session(alphaSession)
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/customers/" + customer.id()).session(alphaSession)
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void generatedTransportModelsValidateRealApiResponsesAndRequiredVersion() throws Exception {
        var created = mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C-SCHEMA\",\"name\":\"架空契約\"}"))
                .andExpect(status().isCreated()).andReturn();
        var mapper = JsonMapper.builder().build();
        var customer = mapper.readValue(created.getResponse().getContentAsString(), CustomerResponse.class);
        assertThat(validator.validate(customer)).isEmpty();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/customers/" + customer.getId());
        var rejected = mvc.perform(put("/api/customers/" + customer.getId())
                        .session(alphaSession).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C-SCHEMA\",\"name\":\"架空契約\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"))
                .andReturn();
        var error = mapper.readValue(rejected.getResponse().getContentAsString(), ApiError.class);
        assertThat(validator.validate(error)).isEmpty();
        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
        mvc.perform(put("/api/customers/not-a-uuid").session(alphaSession).with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"C-SCHEMA\",\"name\":\"架空契約\",\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(service.list(AuthFixtures.ALPHA).getFirst().version()).isZero();
    }

    @Test
    void rejectsJsonTypeCoercionWithoutWritingCustomerOrAudit() throws Exception {
        List<String> invalidCreates = List.of(
                "{\"code\":42,\"name\":\"架空\"}",
                "{\"code\":false,\"name\":\"架空\"}",
                "{\"code\":\"C001\",\"name\":42}",
                "{\"code\":\"C001\",\"name\":0.5}");
        for (String body : invalidCreates) {
            mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isZero();
        Customer customer = service.create(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, new CustomerValues("C001", "original"));
        List<String> invalidUpdates = List.of(
                "{\"code\":\"C001\",\"name\":\"changed\",\"version\":0.5}",
                "{\"code\":\"C001\",\"name\":\"changed\",\"version\":\"0\"}",
                "{\"code\":\"C001\",\"name\":\"changed\",\"version\":true}",
                "{\"code\":42,\"name\":\"changed\",\"version\":0}");
        for (String body : invalidUpdates) {
            mvc.perform(put("/api/customers/" + customer.id()).session(alphaSession).with(csrf())
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(service.list(AuthFixtures.ALPHA)).containsExactly(customer);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void acceptsSupplementaryUnicodeThroughSchemaLimitsAndRejectsOverflowWithoutWrites() throws Exception {
        var mapper = JsonMapper.builder().build();
        for (int length : List.of(30, 50)) {
            int nameLength = length == 30 ? 110 : 200;
            String body = mapper.writeValueAsString(Map.of("code", "𠮷".repeat(length), "name", "𠮷".repeat(nameLength)));
            var created = mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                            .contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andReturn();
            var customer = mapper.readValue(created.getResponse().getContentAsString(), CustomerResponse.class);
            assertThat(validator.validate(customer)).isEmpty();
            assertThat(customer.getName()).isEqualTo("𠮷".repeat(nameLength));
        }
        for (var values : List.of(Map.of("code", "𠮷".repeat(51), "name", "架空"),
                Map.of("code", "C-overflow", "name", "𠮷".repeat(201)))) {
            mvc.perform(post("/api/customers").session(alphaSession).with(csrf())
                            .contentType("application/json").content(mapper.writeValueAsString(values)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(2);
        var customer = service.list(AuthFixtures.ALPHA).getFirst();
        var overflowUpdate = Map.of("code", customer.code(), "name", "𠮷".repeat(201), "version", 0);
        mvc.perform(put("/api/customers/" + customer.id()).session(alphaSession).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(overflowUpdate)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(service.list(AuthFixtures.ALPHA).getFirst()).isEqualTo(customer);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void exactlyOneConcurrentWriterCommitsAndAudits() throws Exception {
        Customer customer = service.create(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, new CustomerValues("C001", "架空"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> writer = () -> {
            ready.countDown();
            start.await();
            try {
                service.update(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, customer.id(), new CustomerValues("C001", "更新"), 0);
                return true;
            } catch (CustomerConflict expected) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(writer);
            var second = executor.submit(writer);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.list(AuthFixtures.ALPHA).getFirst().version()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action LIKE 'CUSTOMER_%'").query(Long.class).single()).isEqualTo(2);
    }
}
