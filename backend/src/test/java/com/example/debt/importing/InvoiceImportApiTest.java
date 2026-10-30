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
class InvoiceImportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean(name = "importClock") Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID customer = UUID.randomUUID();
    private final String code = "I-" + UUID.randomUUID();
    private final List<UUID> batches = new ArrayList<>();
    private MockHttpSession alpha;

    @BeforeEach
    void customerExistsOnlyInAlpha() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T12:00:00Z"));
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name,version) VALUES (?,?,?,'架空請求先',7)")
                .params(customer, AuthFixtures.ALPHA, code).update();
        alpha = AuthFixtures.login(mvc, "alpha-accountant@example.test");
    }

    @AfterEach
    void removeOwnInvoiceFixturesBeforeOtherSuitesResetUsers() {
        jdbc.sql("DELETE FROM invoice_revision WHERE tenant_id=? AND invoice_id IN (SELECT id FROM invoice WHERE customer_id=?)")
                .params(AuthFixtures.ALPHA, customer).update();
        jdbc.sql("DELETE FROM invoice WHERE tenant_id=? AND customer_id=?").params(AuthFixtures.ALPHA, customer).update();
        for (UUID id : batches) {
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                String column = table.equals("import_batch") ? "id" : "batch_id";
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id IN (?,?) AND " + column + "=?")
                        .params(AuthFixtures.ALPHA, AuthFixtures.BETA, id).update();
            }
        }
        jdbc.sql("DELETE FROM customer WHERE tenant_id=? AND id=?").params(AuthFixtures.ALPHA, customer).update();
    }

    @Test
    void previewKeepsInvoiceCustomerAndAuditUnchangedAndFreezesCustomerVersion() throws Exception {
        long audit = count("audit_event");
        long customers = count("customer");
        UUID id = preview(row("INV-1", "2026-10-29", "100"));
        mvc.perform(get("/api/imports/" + id).session(alpha)).andExpect(jsonPath("$.kind").value("INVOICE"))
                .andExpect(jsonPath("$.status").value("PREVIEW_VALID")).andExpect(jsonPath("$.confirmedCount").value(0));
        jdbc.sql("UPDATE customer SET name='架空の変更後名称',version=version+1 WHERE tenant_id=? AND id=?")
                .params(AuthFixtures.ALPHA, customer).update();
        mvc.perform(get("/api/imports/" + id + "/rows").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].customerName").value("架空請求先"))
                .andExpect(jsonPath("$.items[0].invoiceNumber").value("INV-1"))
                .andExpect(jsonPath("$.items[0].amount").value("100"));
        assertThat(jdbc.sql("SELECT reference_snapshot->>'customerVersion' FROM import_row WHERE tenant_id=? AND batch_id=?")
                .params(AuthFixtures.ALPHA, id).query(String.class).single()).isEqualTo("7");
        assertThat(count("invoice")).isZero(); assertThat(count("customer")).isEqualTo(customers);
        assertThat(count("audit_event")).isEqualTo(audit);
        mvc.perform(post("/api/imports/" + id + "/confirm").session(alpha).with(csrf().asHeader())
                .contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_REFERENCE_CONFLICT"));
        assertThat(count("invoice")).isZero();
    }

    @Test
    void repairsInvalidRowsAndKeepsOldErrorsDownloadAndStablePagination() throws Exception {
        UUID invalid = preview(row("INV-1", "2026-10-28", "0") + "MISSING,,bad,bad,-1\n");
        mvc.perform(get("/api/imports/" + invalid).session(alpha)).andExpect(jsonPath("$.status").value("PREVIEW_INVALID"))
                .andExpect(jsonPath("$.errorCount").value(7));
        mvc.perform(get("/api/imports/" + invalid + "/errors?limit=1").session(alpha))
                .andExpect(jsonPath("$.items[0].rowNumber").value(2)).andExpect(jsonPath("$.nextCursor").value(1));
        var csv = mvc.perform(get("/api/imports/" + invalid + "/errors.csv").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"due_date\"", "\"amount\"", "\"customer_code\"");
        UUID repaired = preview(row("INV-1", "2026-10-29", "100"));
        mvc.perform(get("/api/imports/" + repaired).session(alpha)).andExpect(jsonPath("$.status").value("PREVIEW_VALID"));
        mvc.perform(get("/api/imports/" + invalid).session(alpha)).andExpect(jsonPath("$.errorCount").value(7));
        UUID nul = preview(row("INV\0", "2026-10-29", "100"));
        mvc.perform(get("/api/imports/" + nul + "/errors").session(alpha)).andExpect(jsonPath("$.items[0].inputValue").value("INV␀"));
        assertThat(count("invoice")).isZero();
    }

    @Test
    void tenantIsolationAppliesToCustomerLookupEveryReadAndConfirmation() throws Exception {
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        UUID id = preview(row("INV-1", "2026-10-29", "100"));
        for (String suffix : List.of("", "/rows", "/errors", "/errors.csv")) {
            mvc.perform(get("/api/imports/" + id + suffix).session(beta).header("X-Tenant-Id", AuthFixtures.ALPHA))
                    .andExpect(status().isNotFound());
        }
        var missing = mvc.perform(multipart("/api/imports/invoices/preview").file(file(row("INV-2", "2026-10-29", "100")))
                .session(beta).with(csrf().asHeader())).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(missing.getResponse().getContentAsString()).get("status").asString()).isEqualTo("PREVIEW_INVALID");
        batches.add(UUID.fromString(json.readTree(missing.getResponse().getContentAsString()).get("id").asString()));
        mvc.perform(post("/api/imports/" + id + "/confirm").session(beta).with(csrf().asHeader())
                .contentType("application/json").content("{\"version\":0}")).andExpect(status().isNotFound());
    }

    @Test
    void existingCancelledNumbersRemainReservedAndDbRejectsCrossTenantAndInvalidFacts() throws Exception {
        UUID batch = preview(row("INV-RESERVED", "2026-10-29", "100"));
        String sql = "INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id,status,cancelled_at) "
                + "VALUES (?,?,?,?,'2026-10-29','2026-10-29',?,?, 'CANCELLED',CURRENT_TIMESTAMP)";
        jdbc.sql(sql).params(UUID.randomUUID(), AuthFixtures.ALPHA, customer, "INV-RESERVED", 100L, batch).update();
        UUID duplicate = preview(row("INV-RESERVED", "2026-10-29", "100"));
        mvc.perform(get("/api/imports/" + duplicate + "/errors").session(alpha))
                .andExpect(jsonPath("$.items[0].reasonCode").value("INVOICE_EXISTS"));
        assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), AuthFixtures.ALPHA, customer, "INV-RESERVED", 100L, batch).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), AuthFixtures.BETA, customer, "INV-B", 100L, batch).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), AuthFixtures.ALPHA, customer, "INV-ZERO", 0L, batch).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void templateAndPreviewRequireSessionCsrfAndExactlyOneFile() throws Exception {
        mvc.perform(get("/api/imports/invoices/template")).andExpect(status().isUnauthorized());
        var template = mvc.perform(get("/api/imports/invoices/template").session(alpha)).andExpect(status().isOk()).andReturn();
        assertThat(template.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("\ufeff" + InvoiceCsv.HEADER);
        mvc.perform(multipart("/api/imports/invoices/preview").file(file(row("INV-1", "2026-10-29", "1"))).session(alpha))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/invoices/preview").file(file(row("INV-1", "2026-10-29", "1"))).session(alpha)
                .with(csrf().asHeader()).param("tenantId", AuthFixtures.BETA.toString())).andExpect(status().isBadRequest());
    }

    private UUID preview(String rows) throws Exception {
        var result = mvc.perform(multipart("/api/imports/invoices/preview").file(file(rows)).session(alpha).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
        batches.add(id);
        return id;
    }
    private MockMultipartFile file(String rows) {
        return new MockMultipartFile("file", "fictional-invoices.csv", "text/csv", (InvoiceCsv.HEADER + rows).getBytes(StandardCharsets.UTF_8));
    }
    private String row(String number, String due, String amount) { return code + "," + number + ",2026-10-29," + due + "," + amount + "\n"; }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single(); }
}
