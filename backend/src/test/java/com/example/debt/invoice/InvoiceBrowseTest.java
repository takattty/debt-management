package com.example.debt.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvoiceBrowseTest {
    @Autowired InvoiceImportService previews;
    @Autowired ConfirmInvoiceImport confirmation;
    @Autowired InvoiceMutationService mutations;
    @Autowired InvoiceQueries queries;
    @Autowired ReceivablesService receivables;
    @Autowired UserAccounts users;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    private final UUID customer = UUID.randomUUID();
    private final String code = "BROWSE-" + UUID.randomUUID();
    private final List<UUID> batches = new ArrayList<>();
    private TenantUser user;
    private MockHttpSession viewer;

    @BeforeEach
    void fixtures() throws Exception {
        user = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'架空履歴取引先')").params(customer, user.tenantId(), code).update();
        viewer = AuthFixtures.login(mvc, "alpha-admin@example.test");
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.sql("UPDATE app_user SET enabled=true,email_canonical=? WHERE id=?").params(user.email(), user.userId()).update();
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id IN (SELECT id FROM invoice WHERE customer_id=?)")
                .params(user.tenantId(), customer).update();
        jdbc.sql("DELETE FROM invoice_revision WHERE tenant_id=? AND invoice_id IN (SELECT id FROM invoice WHERE customer_id=?)")
                .params(user.tenantId(), customer).update();
        jdbc.sql("DELETE FROM invoice WHERE tenant_id=? AND customer_id=?").params(user.tenantId(), customer).update();
        for (UUID batch : batches) {
            jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id=?").params(user.tenantId(), batch).update();
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id=? AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                        .params(user.tenantId(), batch).update();
            }
        }
        jdbc.sql("DELETE FROM customer WHERE tenant_id=? AND id=?").params(user.tenantId(), customer).update();
    }

    @Test
    void disabledActorCurrentEmailIsReadableWithoutRewritingOriginalRevisionOrAudit() throws Exception {
        UUID id = register("actor", "1500-03-01", 9_007_199_254_740_991L);
        mutations.cancel(user, id, 0, "架空取消");
        String original = trace(id);
        String label = "renamed-" + UUID.randomUUID() + "@example.test";
        jdbc.sql("UPDATE app_user SET enabled=false,email_canonical=? WHERE tenant_id=? AND id=?")
                .params(label, user.tenantId(), user.userId()).update();
        var history = queries.history(user.tenantId(), id, -1, 100).items();
        assertThat(history).hasSize(2).allSatisfy(row -> {
            assertThat(row.actorUserId()).isEqualTo(user.userId());
            assertThat(row.actorEmail()).isEqualTo(label);
        });
        mvc.perform(get("/api/invoices/" + id + "/history?limit=1").session(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].actorUserId").value(user.userId().toString()))
                .andExpect(jsonPath("$.items[0].actorEmail").value(label))
                .andExpect(jsonPath("$.items[0].snapshot.issueDate").value("1500-03-01"));
        assertThat(trace(id)).isEqualTo(original);
        var beta = AuthFixtures.login(mvc, "beta-admin@example.test");
        mvc.perform(get("/api/invoices/" + id + "/history").session(beta)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO invoice_revision(tenant_id,invoice_id,revision,operation,snapshot,actor_user_id,occurred_at)
                SELECT tenant_id,invoice_id,0,'CREATED',snapshot,?,occurred_at FROM invoice_revision
                WHERE tenant_id=? AND invoice_id=? AND revision=0
                ON CONFLICT(tenant_id,invoice_id,revision) DO UPDATE SET actor_user_id=excluded.actor_user_id
                """).params(AuthFixtures.BETA_ADMIN, user.tenantId(), id).update()).isInstanceOf(DataAccessException.class);
        assertThat(trace(id)).isEqualTo(original);
    }

    @Test
    void statusPagesKeepOldDefaultFiltersExactMoneyAndHistoricalDates() throws Exception {
        var dates = List.of("0001-01-01", "1500-03-01", "1582-10-05", "9999-12-31");
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < dates.size(); index++) {
            UUID id = register("date-" + index, dates.get(index), 9_007_199_254_740_991L);
            ids.add(id);
            if (index % 2 == 0) { mutations.cancel(user, id, 0, "架空取消"); }
        }
        var active = receivables.list(user.tenantId(), customer, null, null, null, 100).items();
        assertThat(active).hasSize(2).allSatisfy(row -> assertThat(row.status()).isEqualTo("ACTIVE"));
        for (String selected : List.of("ACTIVE", "CANCELLED", "ALL")) {
            var seen = new ArrayList<UUID>();
            String cursor = null;
            do {
                var page = receivables.list(user.tenantId(), customer, null, null, cursor, 1, selected);
                page.items().forEach(row -> { seen.add(row.id()); assertThat(row.amount()).isEqualTo(9_007_199_254_740_991L); });
                cursor = page.nextCursor();
            } while (cursor != null);
            assertThat(seen).doesNotHaveDuplicates();
            assertThat(seen).hasSize(selected.equals("ALL") ? 4 : 2);
            assertThat(receivables.list(AuthFixtures.BETA, customer, null, null, null, 100, selected).items()).isEmpty();
        }
        assertThat(receivables.list(user.tenantId(), customer, LocalDate.of(1582, 10, 5), LocalDate.of(1582, 10, 5), null, 100, "CANCELLED").items())
                .singleElement().extracting(ReceivablesService.Row::id).isEqualTo(ids.get(2));
        mvc.perform(get("/api/receivables?customerId=" + customer).session(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
        mvc.perform(get("/api/receivables?status=CANCELLED&customerId=" + customer).session(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED")).andExpect(jsonPath("$.items[0].outstandingAmount").value(0))
                .andExpect(jsonPath("$.items[0].amount").value(9_007_199_254_740_991L)).andExpect(jsonPath("$.items[0].dueDate").value("0001-01-01"));
        for (String selected : List.of("bad", "active", "PENDING")) {
            mvc.perform(get("/api/receivables?status=" + selected).session(viewer)).andExpect(status().isBadRequest());
        }
        assertThatThrownBy(() -> receivables.list(user.tenantId(), customer, null, null, null, 101, "ALL")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receivables.list(user.tenantId(), customer, null, null, "bad", 100, "ALL")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenThousandAllStateRowsRemainBoundedAndPreserveOriginalTraces() {
        StringBuilder csv = new StringBuilder(InvoiceCsv.HEADER);
        for (int index = 0; index < 10000; index++) { csv.append(code).append(",BROWSE-").append(UUID.randomUUID()).append(",2026-10-30,2026-11-30,100\n"); }
        UUID batch = previews.preview(user, new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).id();
        batches.add(batch); confirmation.confirm(user, batch, 0);
        var first = receivables.list(user.tenantId(), customer, null, null, null, 100);
        for (var row : first.items()) { mutations.cancel(user, row.id(), 0, "架空の全状態確認"); }
        String original = trace(first.items().getFirst().id());
        long started = System.nanoTime();
        String cursor = null; int count = 0; int cancelled = 0;
        var seen = new java.util.HashSet<UUID>();
        do {
            var page = receivables.list(user.tenantId(), customer, null, null, cursor, 100, "ALL");
            assertThat(page.items()).hasSizeLessThanOrEqualTo(100);
            for (var row : page.items()) { assertThat(seen.add(row.id())).isTrue(); if (row.status().equals("CANCELLED")) { cancelled++; } }
            count += page.items().size(); cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(count).isEqualTo(10000); assertThat(cancelled).isEqualTo(100);
        assertThat(trace(first.items().getFirst().id())).isEqualTo(original);
        var plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM invoice WHERE tenant_id=? ORDER BY due_date,id LIMIT 100")
                .param(user.tenantId()).query(String.class).list();
        System.out.println("INVOICE_ALL_10K browseMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " plan=" + String.join(" | ", plan));
    }

    private UUID register(String number, String date, long amount) {
        var batch = previews.preview(user, new ByteArrayInputStream((InvoiceCsv.HEADER + code + ",BROWSE-" + number + "-" + UUID.randomUUID()
                + "," + date + "," + date + "," + amount + "\n").getBytes(StandardCharsets.UTF_8)));
        batches.add(batch.id()); confirmation.confirm(user, batch.id(), 0);
        return jdbc.sql("SELECT id FROM invoice WHERE tenant_id=? AND source_batch_id=?").params(user.tenantId(), batch.id()).query(UUID.class).single();
    }
    private String trace(UUID id) {
        return jdbc.sql("""
                SELECT jsonb_build_object('revisions',(SELECT jsonb_agg(to_jsonb(r) ORDER BY revision) FROM invoice_revision r WHERE tenant_id=:tenant AND invoice_id=:id),
                    'audits',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM audit_event a WHERE tenant_id=:tenant AND entity_id=:id))::text
                """).param("tenant", user.tenantId()).param("id", id).query(String.class).single();
    }
}
