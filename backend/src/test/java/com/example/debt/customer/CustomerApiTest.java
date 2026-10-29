package com.example.debt.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.DevelopmentAccess;
import java.util.List;
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
@ActiveProfiles("dev")
class CustomerApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CustomerService service;

    @BeforeEach
    void clearFictionalData() {
        jdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM customer").update();
    }

    @Test
    void persistsCustomerAndAuditAndSeparatesTenants() throws Exception {
        mvc.perform(post("/api/customers").with(httpBasic("alpha-admin", "debt_local")).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空商事\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/customers").with(httpBasic("alpha-admin", "debt_local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("架空商事"));
        mvc.perform(get("/api/customers").with(httpBasic("beta-admin", "debt_local"))
                        .header("X-Tenant-Id", DevelopmentAccess.ALPHA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'CUSTOMER_CREATED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidAndDuplicateRowsWithoutCreatingAdditionalAudit() throws Exception {
        mvc.perform(post("/api/customers").with(httpBasic("alpha-admin", "debt_local")).with(csrf())
                        .contentType("application/json").content("{\"code\":\"\",\"name\":\"架空\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"));
        CustomerValues values = new CustomerValues("C001", "架空");
        service.create(DevelopmentAccess.ALPHA, "alpha-admin", values);
        mvc.perform(post("/api/customers").with(httpBasic("alpha-admin", "debt_local")).with(csrf())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"別名\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER_CODE"));
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isEqualTo(1);
        service.create(DevelopmentAccess.BETA, "beta-admin", values);
        assertThat(jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void refusesUnauthenticatedAndMissingCsrfRequests() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers").with(httpBasic("alpha-admin", "debt_local"))
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/session").with(httpBasic("alpha-admin", "debt_local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void issuedCsrfTokenAuthorizesMutationAndLogoutInvalidatesSession() throws Exception {
        var login = mvc.perform(get("/api/session").with(httpBasic("alpha-admin", "debt_local")))
                .andExpect(status().isOk()).andReturn();
        var token = JsonMapper.builder().build().readTree(login.getResponse().getContentAsString());
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(post("/api/customers").with(httpBasic("alpha-admin", "debt_local"))
                        .session(session).header(token.get("csrfHeaderName").asText(), token.get("csrfToken").asText())
                        .contentType("application/json").content("{\"code\":\"C001\",\"name\":\"架空\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/logout").with(httpBasic("alpha-admin", "debt_local"))
                        .session(session).header(token.get("csrfHeaderName").asText(), token.get("csrfToken").asText()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void preventsCrossTenantUpdateAndStaleVersion() throws Exception {
        Customer customer = service.create(DevelopmentAccess.ALPHA, "alpha-admin", new CustomerValues("C001", "架空"));
        String body = "{\"code\":\"C001\",\"name\":\"改訂\",\"version\":0}";
        mvc.perform(put("/api/customers/" + customer.id()).with(httpBasic("beta-admin", "debt_local"))
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/customers/" + customer.id()).with(httpBasic("alpha-admin", "debt_local"))
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/customers/" + customer.id()).with(httpBasic("alpha-admin", "debt_local"))
                        .with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void exactlyOneConcurrentWriterCommitsAndAudits() throws Exception {
        Customer customer = service.create(DevelopmentAccess.ALPHA, "alpha-admin", new CustomerValues("C001", "架空"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> writer = () -> {
            ready.countDown();
            start.await();
            try {
                service.update(DevelopmentAccess.ALPHA, "alpha-admin", customer.id(), new CustomerValues("C001", "更新"), 0);
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
        assertThat(service.list(DevelopmentAccess.ALPHA).getFirst().version()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isEqualTo(2);
    }
}
