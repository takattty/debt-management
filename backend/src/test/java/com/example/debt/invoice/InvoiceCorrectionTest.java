package com.example.debt.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.importing.ConfirmInvoiceImport;
import com.example.debt.importing.InvoiceCsv;
import com.example.debt.importing.InvoiceImportService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvoiceCorrectionTest {
    @Autowired InvoiceImportService previews;
    @Autowired ConfirmInvoiceImport confirmation;
    @Autowired InvoiceMutationService mutations;
    @Autowired InvoiceQueries queries;
    @Autowired UserAccounts users;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean InvoiceMutationEligibility eligibility;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID customer = UUID.randomUUID();
    private final UUID betaCustomer = UUID.randomUUID();
    private final String code = "C-" + UUID.randomUUID();
    private TenantUser user;
    private UUID batch;
    private UUID invoice;
    private MockHttpSession alpha;

    @BeforeEach
    void confirmedInvoiceWithAnImmutableInitialRevision() throws Exception {
        user = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'架空訂正先')").params(customer, user.tenantId(), code).update();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'架空の別企業顧客')")
                .params(betaCustomer, AuthFixtures.BETA, "B-" + UUID.randomUUID()).update();
        batch = previews.preview(user, new ByteArrayInputStream((InvoiceCsv.HEADER + code + ",INV-" + UUID.randomUUID()
                + ",2026-10-29,2026-11-30,100000\n").getBytes(StandardCharsets.UTF_8))).id();
        confirmation.confirm(user, batch, 0);
        invoice = jdbc.sql("SELECT id FROM invoice WHERE tenant_id=? AND source_batch_id=?").params(user.tenantId(), batch).query(UUID.class).single();
        alpha = AuthFixtures.login(mvc, user.email());
    }

    @AfterEach
    void removeOwnFixtures() {
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id IN (?,?)").params(user.tenantId(), batch, invoice).update();
        jdbc.sql("DELETE FROM invoice_revision WHERE tenant_id=? AND invoice_id=?").params(user.tenantId(), invoice).update();
        jdbc.sql("DELETE FROM invoice WHERE tenant_id=? AND id=?").params(user.tenantId(), invoice).update();
        for (String table : List.of("import_error", "import_row", "import_batch")) {
            jdbc.sql("DELETE FROM " + table + " WHERE tenant_id=? AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                    .params(user.tenantId(), batch).update();
        }
        jdbc.sql("DELETE FROM customer WHERE id IN (?,?)").params(customer, betaCustomer).update();
    }

    @Test
    void correctionsPreserveSupportedIsoDatesInResponsesRevisionsAndAudit() throws Exception {
        var dates = List.of("0001-01-01", "1500-03-01", "1582-10-04", "1582-10-05", "1582-10-10", "1582-10-15", "9999-12-31");
        for (int version = 0; version < dates.size(); version++) {
            String date = dates.get(version);
            String request = body(version, 110000L, "日付の訂正").replace("2026-10-29", date).replace("2026-11-30", date);
            mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json").content(request))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.issueDate").value(date)).andExpect(jsonPath("$.dueDate").value(date));
            mvc.perform(get("/api/invoices/" + invoice).session(alpha)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.issueDate").value(date)).andExpect(jsonPath("$.dueDate").value(date));
            var snapshot = queries.history(user.tenantId(), invoice, version, 100).items().getFirst().snapshot();
            assertThat(snapshot.issueDate().toString()).isEqualTo(date);
            assertThat(snapshot.dueDate().toString()).isEqualTo(date);
            assertThat(jdbc.sql("SELECT issue_date::text || '/' || due_date::text FROM invoice WHERE tenant_id=? AND id=?")
                    .params(user.tenantId(), invoice).query(String.class).single()).isEqualTo(date + "/" + date);
            assertThat(jdbc.sql("SELECT (details->'after'->>'issueDate') || '/' || (details->'after'->>'dueDate') FROM audit_event "
                    + "WHERE tenant_id=? AND entity_id=? AND action='INVOICE_CORRECTED' AND (details->'after'->>'version')::bigint=?")
                    .params(user.tenantId(), invoice, version + 1).query(String.class).single()).isEqualTo(date + "/" + date);
        }
    }

    @Test
    void correctionUpdatesOutstandingAndAppendsHistoryWithoutChangingNumberOrInitialFacts() throws Exception {
        var original = queries.get(user.tenantId(), invoice, false);
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(body(0, 110000L, " 税込金額の訂正 "))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.amount").value(110000))
                .andExpect(jsonPath("$.outstandingAmount").value(110000)).andExpect(jsonPath("$.invoiceNumber").value(original.invoiceNumber()));
        mvc.perform(get("/api/invoices/" + invoice + "/history?limit=1").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].operation").value("CREATED")).andExpect(jsonPath("$.items[0].snapshot.amount").value(100000))
                .andExpect(jsonPath("$.nextCursor").value(0));
        mvc.perform(get("/api/invoices/" + invoice + "/history?cursor=0&limit=1").session(alpha))
                .andExpect(jsonPath("$.items[0].operation").value("CORRECTED")).andExpect(jsonPath("$.items[0].reason").value("税込金額の訂正"))
                .andExpect(jsonPath("$.items[0].snapshot.amount").value(110000));
        mvc.perform(get("/api/receivables?customerId=" + customer).session(alpha)).andExpect(jsonPath("$.items[0].outstandingAmount").value(110000));
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(body(0, 120000L, "古い画面"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(2);
    }

    @Test
    void rejectsTenantReferencesNumberChangesMissingReasonCsrfAndInvalidAmountsWithoutAnyMutation() throws Exception {
        for (String body : List.of(body(0, 0L, "理由"), body(0, -1L, "理由"), body(0, 1L, " "),
                body(0, 1L, "理由").replace("\"amount\":1", "\"amount\":1.5"),
                body(0, 1L, "理由").replace("\"amount\":1", "\"amount\":\"1\""),
                body(0, 1L, "理由").replace("}", ",\"invoiceNumber\":\"CHANGED\"}"))) {
            mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).contentType("application/json").content(body(0, 1L, "理由")))
                .andExpect(status().isForbidden());
        var fields = new java.util.HashMap<>(json.readValue(body(0, 110000L, "理由"), Map.class)); fields.put("customerId", betaCustomer.toString());
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(json.writeValueAsString(fields))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("customerId"));
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        mvc.perform(get("/api/invoices/" + invoice).session(beta)).andExpect(status().isNotFound());
        mvc.perform(get("/api/invoices/" + invoice + "/history").session(beta)).andExpect(status().isNotFound());
        mvc.perform(put("/api/invoices/" + invoice).session(beta).with(csrf().asHeader()).contentType("application/json")
                .content(body(0, 110000L, "越境"))).andExpect(status().isNotFound());
        assertThat(queries.get(user.tenantId(), invoice, false).version()).isZero();
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(1);
    }

    @Test
    void reasonUsesCodePointsAndCancelledInvoicesCannotBeCorrected() throws Exception {
        String reason = "𠮷".repeat(1000);
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(body(0, 110000L, reason))).andExpect(status().isOk());
        assertThat(queries.history(user.tenantId(), invoice, 0, 100).items().getFirst().reason()).isEqualTo(reason);
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?")
                .params(user.tenantId(), invoice).update();
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(body(1, 120000L, "取消後の訂正"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_NOT_ACTIVE"));
    }

    @Test
    void eligibilityDenialFixtureVerifiesPortPathAndKeepsInvoiceAndHistoryUnchanged() throws Exception {
        // No real Settlement ledger exists yet; this tests the rejection port, not a real allocated invoice.
        doThrow(new InvoiceConflict("INVOICE_ALLOCATED")).when(eligibility).requireUnallocated(user.tenantId(), invoice);
        mvc.perform(put("/api/invoices/" + invoice).session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(body(0, 110000L, "訂正"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALLOCATED"));
        assertThat(queries.get(user.tenantId(), invoice, false).version()).isZero();
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(1);
    }

    @Test
    void concurrentCorrectionsWithSameVersionHaveOneWinnerAndOneRevision() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.Stream.of(110000L, 120000L).map(amount -> pool.submit(() -> {
                start.await(); try { return correct(amount, "同時訂正"); } catch (InvoiceConflict conflict) { return conflict; }
            })).toList();
            start.countDown();
            var results = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(InvoiceState.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(InvoiceConflict.class::isInstance).map(InvoiceConflict.class::cast)).singleElement()
                    .extracting(InvoiceConflict::code).isEqualTo("VERSION_CONFLICT");
        }
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(2);
    }

    @Test
    void auditFailureRollsBackCorrectionAndItsRevision() {
        jdbc.sql("CREATE FUNCTION reject_correction_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.action='INVOICE_CORRECTED' THEN RAISE EXCEPTION 'Fictional rejection'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_correction_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_correction_fixture()").update();
        try {
            assertThatThrownBy(() -> correct(110000L, "監査失敗の検証")).isInstanceOf(DataAccessException.class);
            assertThat(queries.get(user.tenantId(), invoice, false).amount()).isEqualTo(100000);
            assertThat(queries.get(user.tenantId(), invoice, false).version()).isZero();
            assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(1);
        } finally {
            jdbc.sql("DROP TRIGGER reject_correction_fixture ON audit_event").update(); jdbc.sql("DROP FUNCTION reject_correction_fixture()").update();
        }
    }

    private InvoiceState correct(long amount, String reason) {
        return mutations.correct(user, invoice, 0, customer, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 30), amount, reason);
    }

    @Test
    void cancellationKeepsOriginalFactsAndReasonExcludesReceivableAndPermanentlyReservesNumber() throws Exception {
        String number = queries.get(user.tenantId(), invoice, false).invoiceNumber();
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":0,\"reason\":\" 重複請求の取消 \"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.amount").value(100000)).andExpect(jsonPath("$.outstandingAmount").value(0))
                .andExpect(jsonPath("$.invoiceNumber").value(number)).andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        mvc.perform(get("/api/receivables?customerId=" + customer).session(alpha)).andExpect(jsonPath("$.items").isEmpty());
        var history = queries.history(user.tenantId(), invoice, -1, 100).items();
        assertThat(history).hasSize(2); assertThat(history.getFirst().snapshot().amount()).isEqualTo(100000);
        assertThat(history.getLast().operation()).isEqualTo("CANCELLED");
        assertThat(history.getLast().reason()).isEqualTo("重複請求の取消");
        assertThatThrownBy(() -> mutations.cancel(user, invoice, 1, "二重取消")).isInstanceOf(InvoiceConflict.class).hasMessage("INVOICE_NOT_ACTIVE");
        assertThatThrownBy(() -> correct(110000L, "取消後の訂正")).isInstanceOf(InvoiceConflict.class);
        var preview = previews.preview(user, new ByteArrayInputStream((InvoiceCsv.HEADER + code + "," + number
                + ",2026-10-29,2026-11-30,200000\n").getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(preview.errorCount()).isEqualTo(1);
            assertThat(preview.status().name()).isEqualTo("PREVIEW_INVALID");
        } finally {
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id=? AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                        .params(user.tenantId(), preview.id()).update();
            }
        }
    }

    @Test
    void cancellationRequiresReasonCsrfTenantAndEligibilityAndNeverMutatesOnDenial() throws Exception {
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").session(alpha).contentType("application/json")
                .content("{\"version\":0,\"reason\":\"取消\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":0,\"reason\":\" \"}")).andExpect(status().isBadRequest());
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").session(beta).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":0,\"reason\":\"越境\"}")).andExpect(status().isNotFound());
        doThrow(new InvoiceConflict("INVOICE_ALLOCATED")).when(eligibility).requireUnallocated(user.tenantId(), invoice);
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":0,\"reason\":\"取消\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALLOCATED"));
        assertThat(queries.get(user.tenantId(), invoice, false).status()).isEqualTo("ACTIVE");
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(1);
    }

    @Test
    void auditFailureRollsBackCancellationTimeStatusRevisionAndVersion() {
        jdbc.sql("CREATE FUNCTION reject_cancel_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.action='INVOICE_CANCELLED' THEN RAISE EXCEPTION 'Fictional rejection'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_cancel_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_cancel_fixture()").update();
        try {
            assertThatThrownBy(() -> mutations.cancel(user, invoice, 0, "監査失敗の取消検証")).isInstanceOf(DataAccessException.class);
            var state = queries.get(user.tenantId(), invoice, false);
            assertThat(state.status()).isEqualTo("ACTIVE"); assertThat(state.cancelledAt()).isNull(); assertThat(state.version()).isZero();
            assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(1);
        } finally {
            jdbc.sql("DROP TRIGGER reject_cancel_fixture ON audit_event").update(); jdbc.sql("DROP FUNCTION reject_cancel_fixture()").update();
        }
    }

    @Test
    void cancellationAgainstCorrectionUsesSameInvoiceLockAndVersion() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancel = pool.submit(() -> {
                start.await(); try { return mutations.cancel(user, invoice, 0, "同時取消"); } catch (InvoiceConflict conflict) { return conflict; }
            });
            var correction = pool.submit(() -> {
                start.await(); try { return correct(110000L, "同時訂正"); } catch (InvoiceConflict conflict) { return conflict; }
            });
            start.countDown();
            var results = List.of(cancel.get(10, TimeUnit.SECONDS), correction.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(InvoiceState.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(InvoiceConflict.class::isInstance).map(InvoiceConflict.class::cast)).singleElement()
                    .extracting(InvoiceConflict::code).isEqualTo("VERSION_CONFLICT");
        }
        assertThat(queries.get(user.tenantId(), invoice, false).version()).isEqualTo(1);
        assertThat(queries.history(user.tenantId(), invoice, -1, 100).items()).hasSize(2);
    }
    private String body(long version, long amount, String reason) {
        return json.writeValueAsString(Map.of("version", version, "customerId", customer, "issueDate", "2026-10-29", "dueDate", "2026-11-30", "amount", amount, "reason", reason));
    }
}
