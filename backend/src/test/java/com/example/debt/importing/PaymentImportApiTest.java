package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class PaymentImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean(name = "importClock") Clock clock;
    private final List<UUID> batches = new ArrayList<>();
    private MockHttpSession alpha;

    @BeforeEach
    void login() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-30T12:00:00Z"));
        alpha = AuthFixtures.login(mvc, "alpha-accountant@example.test");
    }

    @AfterEach
    void cleanupOwnFixtures() {
        // Test-only reset: ordinary UPDATE/DELETE remain blocked by the immutable-facts trigger.
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id IN (?,?) AND entity_id IN (SELECT id FROM payment)")
                .params(AuthFixtures.ALPHA, AuthFixtures.BETA).update();
        jdbc.sql("TRUNCATE payment").update();
        for (UUID batch : batches) {
            jdbc.sql("DELETE FROM audit_event WHERE tenant_id IN (?,?) AND entity_id=?")
                    .params(AuthFixtures.ALPHA, AuthFixtures.BETA, batch).update();
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id IN (?,?) AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                        .params(AuthFixtures.ALPHA, AuthFixtures.BETA, batch).update();
            }
        }
    }

    @Test
    void previewIsReadOnlyPagedFrozenAndCannotBeMisdispatched() throws Exception {
        long audit = count("audit_event"), customers = count("customer"), invoices = count("invoice");
        UUID id = preview(" T1 ,2026-10-30, 架空 ,000100\nT2,2026-10-31,架空2,200\n", alpha);
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(jsonPath("$.kind").value("PAYMENT"))
                .andExpect(jsonPath("$.status").value("PREVIEW_VALID")).andExpect(jsonPath("$.confirmedCount").value(0));
        mvc.perform(get("/api/imports/" + id + "/payment-rows?limit=1").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].transactionId").value("T1")).andExpect(jsonPath("$.items[0].payerName").value("架空"))
                .andExpect(jsonPath("$.items[0].amount").value("100")).andExpect(jsonPath("$.nextCursor").value(1));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-02T12:00:00Z"));
        mvc.perform(get("/api/imports/" + id + "/payment-rows?cursor=1&limit=1").session(alpha))
                .andExpect(jsonPath("$.items[0].transactionId").value("T2")).andExpect(jsonPath("$.nextCursor").value(0));
        mvc.perform(get("/api/imports/" + id + "/rows").session(alpha)).andExpect(status().isConflict());
        mvc.perform(post("/api/imports/" + id + "/confirm").session(alpha).with(csrf().asHeader())
                .contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_EXPIRED"));
        assertThat(count("payment")).isZero(); assertThat(count("audit_event")).isEqualTo(audit);
        assertThat(count("customer")).isEqualTo(customers); assertThat(count("invoice")).isEqualTo(invoices);
        assertThat(preview("T1,2026-10-30,架空,100\n", alpha)).isNotEqualTo(id);
    }

    @Test
    void invalidMixedBatchRetainsErrorsAndCanBeRepairedWithoutPartialPayment() throws Exception {
        UUID id = preview("T1,2026-10-30,架空,100\nT2,2026-02-30,=名義,0\nT3,\0,架空,-1\n", alpha);
        mvc.perform(get("/api/imports/" + id + "/errors?limit=1").session(alpha)).andExpect(jsonPath("$.items[0].rowNumber").value(3))
                .andExpect(jsonPath("$.items[0].field").value("transfer_date")).andExpect(jsonPath("$.nextCursor").value(1));
        var csv = mvc.perform(get("/api/imports/" + id + "/errors.csv").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"3\",\"amount\",\"0\"", "␀");
        UUID repaired = preview("T1,2026-10-30,架空,100\nT2,2026-02-28,=名義,1\n", alpha);
        mvc.perform(get("/api/imports/" + repaired).session(alpha)).andExpect(jsonPath("$.errorCount").value(0));
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(jsonPath("$.errorCount").value(4));
        assertThat(count("payment")).isZero();
    }

    @Test
    void tenantIsolationAndDbFactConstraintsReserveTransactionIds() throws Exception {
        UUID batch = preview("RESERVED,2026-10-30,架空,100\n", alpha);
        String insert = "INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) "
                + "VALUES (?,?,?,'2026-10-30','架空',?,?,?,CURRENT_TIMESTAMP)";
        jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.ALPHA, "RESERVED", 100L, batch, AuthFixtures.ALPHA_ADMIN).update();
        for (String sql : List.of("UPDATE payment SET amount_jpy=200 WHERE tenant_id=?", "DELETE FROM payment WHERE tenant_id=?")) {
            assertThatThrownBy(() -> jdbc.sql(sql).param(AuthFixtures.ALPHA).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (long amount : List.of(0L, -1L, 9007199254740992L)) {
            assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.ALPHA, "INVALID", amount, batch, AuthFixtures.ALPHA_ADMIN).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.ALPHA, "RESERVED", 100L, batch, AuthFixtures.ALPHA_ADMIN).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.BETA, "T", 1L, batch, AuthFixtures.BETA_ADMIN).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.ALPHA, "T", 1L, batch, AuthFixtures.BETA_ADMIN).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String date : List.of("0001-01-01 BC", "10000-01-01")) {
            assertThatThrownBy(() -> jdbc.sql(insert.replace("2026-10-30", date))
                    .params(UUID.randomUUID(), AuthFixtures.ALPHA, "DATE", 1L, batch, AuthFixtures.ALPHA_ADMIN).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        UUID customerBatch = UUID.randomUUID(); batches.add(customerBatch);
        jdbc.sql("INSERT INTO import_batch SELECT ?,tenant_id,'CUSTOMER',format_version,semantic_fingerprint,status,version,created_by_user_id,created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count FROM import_batch WHERE tenant_id=? AND id=?")
                .params(customerBatch, AuthFixtures.ALPHA, batch).update();
        assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), AuthFixtures.ALPHA, "KIND", 1L, customerBatch, AuthFixtures.ALPHA_ADMIN).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        for (String suffix : List.of("", "/rows", "/payment-rows", "/errors", "/errors.csv")) {
            mvc.perform(get("/api/imports/" + batch + suffix).session(beta).header("X-Tenant-Id", AuthFixtures.ALPHA)).andExpect(status().isNotFound());
        }
        UUID duplicate = preview("RESERVED,2026-10-30,架空,100\n", alpha);
        mvc.perform(get("/api/imports/" + duplicate + "/errors").session(alpha)).andExpect(jsonPath("$.items[0].reasonCode").value("PAYMENT_EXISTS"));
        UUID separate = preview("RESERVED,2026-10-30,架空,100\n", beta);
        mvc.perform(get("/api/imports/" + separate).session(beta)).andExpect(jsonPath("$.status").value("PREVIEW_VALID"));
    }

    @Test
    void sessionCsrfMultipartAndPaginationAreEnforced() throws Exception {
        mvc.perform(get("/api/imports/payments/template")).andExpect(status().isUnauthorized());
        var template = mvc.perform(get("/api/imports/payments/template").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(template.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("\ufeff" + PaymentCsv.HEADER);
        mvc.perform(multipart("/api/imports/payments/preview").file(file("")).session(alpha)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/payments/preview").file(file("")).session(alpha).with(csrf().asHeader())
                .param("tenantId", AuthFixtures.BETA.toString())).andExpect(status().isBadRequest());
        UUID id = preview("T,2026-10-30,架空,1\n", alpha);
        mvc.perform(get("/api/imports/" + id + "/payment-rows?limit=101").session(alpha)).andExpect(status().isBadRequest());
    }

    @Test
    void confirmsAllFactsOnceAndListsTenantScopedImmutablePayments() throws Exception {
        UUID id = preview("HTTP-A,2026-10-30,架空A,100\nHTTP-B,2026-10-31,架空B,200\n", alpha);
        String confirm = "/api/imports/" + id + "/confirm";
        mvc.perform(post(confirm).session(alpha).contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        for (int ignored = 0; ignored < 2; ignored++) {
            mvc.perform(post(confirm).session(alpha).with(csrf().asHeader()).contentType("application/json").content("{\"version\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedCount").value(2));
        }
        mvc.perform(get("/api/payments?dateFrom=2026-10-31&dateTo=2026-10-31").session(alpha))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].transactionId").value("HTTP-B")).andExpect(jsonPath("$.items[0].amount").value(200))
                .andExpect(jsonPath("$.items[0].unallocatedAmount").value(200)).andExpect(jsonPath("$.items[0].sourceBatchId").value(id.toString()));
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        for (String query : List.of("limit=101", "cursor=bad", "dateFrom=2026-11-01&dateTo=2026-10-01")) {
            mvc.perform(get("/api/payments?" + query).session(alpha)).andExpect(status().isBadRequest());
        }
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        mvc.perform(get("/api/payments").session(beta).header("X-Tenant-Id", AuthFixtures.ALPHA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(count("payment")).isEqualTo(2);
    }

    private UUID preview(String rows, MockHttpSession session) throws Exception {
        var result = mvc.perform(multipart("/api/imports/payments/preview").file(file(rows)).session(session).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("id").asString());
        batches.add(id); return id;
    }
    private MockMultipartFile file(String rows) {
        return new MockMultipartFile("file", "fictional-payments.csv", "text/csv", (PaymentCsv.HEADER + rows).getBytes(StandardCharsets.UTF_8));
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id=?").param(AuthFixtures.ALPHA).query(Long.class).single(); }
}
