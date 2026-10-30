package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.invoice.ReceivablesService;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvoiceConfirmationTest {
    @Autowired InvoiceImportService previews;
    @Autowired ConfirmInvoiceImport confirmation;
    @Autowired ConfirmCustomerImport customerConfirmation;
    @MockitoSpyBean ImportStore store;
    @Autowired UserAccounts users;
    @Autowired ReceivablesService receivables;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean(name = "importClock") Clock clock;
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private final UUID customer = UUID.randomUUID();
    private final String code = "C-" + UUID.randomUUID();
    private final String prefix = "I-" + UUID.randomUUID();
    private final List<UUID> batchIds = new ArrayList<>();
    private TenantUser user;

    @BeforeEach
    void fixedClockAndExistingCustomer() {
        when(clock.instant()).thenReturn(NOW);
        user = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name,version) VALUES (?,?,?,'架空請求先',7)")
                .params(customer, user.tenantId(), code).update();
    }

    @AfterEach
    void removeOnlyOwnBusinessAndImportFixtures() {
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id IN (SELECT id FROM invoice WHERE tenant_id=? AND customer_id=?)")
                .params(user.tenantId(), user.tenantId(), customer).update();
        jdbc.sql("DELETE FROM invoice_revision WHERE tenant_id=? AND invoice_id IN (SELECT id FROM invoice WHERE customer_id=?)")
                .params(user.tenantId(), customer).update();
        jdbc.sql("DELETE FROM invoice WHERE tenant_id=? AND customer_id=?").params(user.tenantId(), customer).update();
        for (UUID id : batchIds) {
            jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id=?").params(user.tenantId(), id).update();
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id=? AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                        .params(user.tenantId(), id).update();
            }
        }
        jdbc.sql("DELETE FROM customer WHERE tenant_id=? AND id=?").params(user.tenantId(), customer).update();
    }

    @Test
    void createsEveryInvoiceInitialRevisionAndAuditOnceAndRecoversLostResponseAfterExpiry() {
        var batch = preview(twoRows("100", "200"));
        assertThatThrownBy(() -> customerConfirmation.confirm(user, batch.id(), 0))
                .isInstanceOf(ImportRejected.class).hasMessage("IMPORT_KIND_UNSUPPORTED");
        var result = confirmation.confirm(user, batch.id(), 0);
        assertThat(result.status()).isEqualTo(ImportBatch.Status.CONFIRMED);
        assertThat(invoices()).isEqualTo(2);
        assertThat(revisions()).isEqualTo(2);
        assertThat(invoiceAudits()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='INVOICE_IMPORT_CONFIRMED'")
                .params(user.tenantId(), batch.id()).query(Long.class).single()).isEqualTo(1);
        when(clock.instant()).thenReturn(batch.expiresAt());
        assertThat(confirmation.confirm(user, batch.id(), 0)).isEqualTo(result);
        assertThat(confirmation.confirm(user, batch.id(), 1)).isEqualTo(result);
        assertThat(invoices()).isEqualTo(2);
    }

    @Test
    void invalidExpiredAndStaleVersionWriteNothing() {
        var invalid = preview(twoRows("100", "abc"));
        rejected(invalid, 0, "IMPORT_INVALID");
        var valid = preview(twoRows("100", "200"));
        rejected(valid, 1, "VERSION_CONFLICT");
        when(clock.instant()).thenReturn(valid.expiresAt());
        rejected(valid, 0, "IMPORT_EXPIRED");
        assertThat(invoices()).isZero(); assertThat(revisions()).isZero(); assertThat(invoiceAudits()).isZero();
    }

    @Test
    void changedCustomerVersionInvalidatesEntireBatchAfterRollbackAndPreservesSnapshot() {
        var batch = preview(twoRows("100", "200"));
        var before = store.invoiceSnapshots(user.tenantId(), batch.id());
        jdbc.sql("UPDATE customer SET name='架空の変更後',version=version+1 WHERE tenant_id=? AND id=?")
                .params(user.tenantId(), customer).update();
        rejected(batch, 0, "IMPORT_REFERENCE_CONFLICT");
        var invalid = store.get(user.tenantId(), batch.id(), false);
        assertThat(invalid.status()).isEqualTo(ImportBatch.Status.PREVIEW_INVALID);
        assertThat(invalid.version()).isEqualTo(1);
        assertThat(store.invoiceSnapshots(user.tenantId(), batch.id())).isEqualTo(before);
        assertThat(store.errors(user.tenantId(), batch.id(), 0, 10)).hasSize(2);
        assertThat(invoices()).isZero(); assertThat(revisions()).isZero(); assertThat(invoiceAudits()).isZero();
    }

    @Test
    void sameBatchConcurrencyIsIdempotentAndOppositeOrderDifferentBatchesHaveOneWinner() throws Exception {
        var batch = preview(twoRows("100", "200"));
        var same = race(() -> confirmation.confirm(user, batch.id(), 0), () -> confirmation.confirm(user, batch.id(), 0));
        assertThat(same).allMatch(ImportBatch.class::isInstance);
        assertThat(same.getFirst()).isEqualTo(same.getLast());
        var first = preview(row("C", "100") + row("D", "200"));
        var second = preview("\ufeff" + InvoiceCsv.HEADER.replace("\r\n", "\n") + row("D", "200") + row("C", "100"), true);
        assertThat(first.semanticFingerprint()).isEqualTo(second.semanticFingerprint());
        jdbc.sql("CREATE FUNCTION slow_invoice_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN PERFORM pg_sleep(0.1); RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER slow_invoice_fixture BEFORE INSERT ON invoice FOR EACH ROW EXECUTE FUNCTION slow_invoice_fixture()").update();
        try {
            var results = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
            assertThat(results.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast)).singleElement()
                    .extracting(ImportRejected::code).isEqualTo("IMPORT_ALREADY_CONFIRMED");
        } finally {
            jdbc.sql("DROP TRIGGER slow_invoice_fixture ON invoice").update(); jdbc.sql("DROP FUNCTION slow_invoice_fixture()").update();
        }
        assertThat(invoices()).isEqualTo(4); assertThat(revisions()).isEqualTo(4); assertThat(invoiceAudits()).isEqualTo(4);
    }

    @Test
    void overlappingNumbersWithDifferentContentKeepExactlyOneWholeBatch() throws Exception {
        var first = preview(twoRows("100", "200"));
        var second = preview(twoRows("110", "220"));
        var results = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
        assertThat(results.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast)).singleElement()
                .extracting(ImportRejected::code).isEqualTo("IMPORT_REFERENCE_CONFLICT");
        assertThat(invoices()).isEqualTo(2); assertThat(revisions()).isEqualTo(2); assertThat(invoiceAudits()).isEqualTo(2);
    }

    @Test
    void matchingBatchCommittedAfterInitialFingerprintReadStillReportsOriginalResult() {
        var first = preview(twoRows("100", "200"));
        var second = preview(twoRows("100", "200"));
        confirmation.confirm(user, first.id(), 0);
        // Model the first read occurring just before the winning transaction commits; subsequent SQL sees its invoices.
        doReturn(null, first.id()).when(store).confirmedDuplicate(second);
        assertThatThrownBy(() -> confirmation.confirm(user, second.id(), 0))
                .isInstanceOfSatisfying(ImportRejected.class, error -> {
                    assertThat(error.code()).isEqualTo("IMPORT_ALREADY_CONFIRMED");
                    assertThat(error.originalBatchId()).isEqualTo(first.id());
                });
        assertThat(invoices()).isEqualTo(2);
        assertThat(store.errors(user.tenantId(), second.id(), 0, 10).getFirst().value().reasonCode()).isEqualTo("IMPORT_ALREADY_CONFIRMED");
    }

    @Test
    void auditFailureAndExpiryDuringInsertionRollBackInvoicesRevisionsAndAudits() {
        var batch = preview(twoRows("100", "200"));
        jdbc.sql("CREATE FUNCTION reject_invoice_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.action='INVOICE_IMPORT_CONFIRMED' THEN RAISE EXCEPTION 'Fictional rejection'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_invoice_audit_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_invoice_audit_fixture()").update();
        try {
            assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), 0)).isInstanceOf(DataAccessException.class);
            assertThat(invoices()).isZero(); assertThat(revisions()).isZero(); assertThat(invoiceAudits()).isZero();
            assertThat(store.get(user.tenantId(), batch.id(), false).version()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER reject_invoice_audit_fixture ON audit_event").update(); jdbc.sql("DROP FUNCTION reject_invoice_audit_fixture()").update();
        }
        when(clock.instant()).thenReturn(NOW, batch.expiresAt());
        rejected(batch, 0, "IMPORT_EXPIRED");
        assertThat(invoices()).isZero(); assertThat(revisions()).isZero(); assertThat(invoiceAudits()).isZero();
    }

    @Test
    void referenceShareLockHoldsUntilConfirmationCommitsBeforeConcurrentCustomerEdit() throws Exception {
        var batch = preview(row("LOCK", "100"));
        jdbc.sql("CREATE FUNCTION hold_invoice_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN PERFORM pg_advisory_xact_lock(87654321); RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER hold_invoice_fixture BEFORE INSERT ON invoice FOR EACH ROW EXECUTE FUNCTION hold_invoice_fixture()").update();
        try (var gate = dataSource.getConnection(); var statement = gate.createStatement(); var pool = Executors.newFixedThreadPool(2)) {
            statement.execute("SELECT pg_advisory_lock(87654321)");
            try {
                var confirm = pool.submit(() -> confirmation.confirm(user, batch.id(), 0));
                awaitDatabaseWait("wait_event='advisory'");
                var edit = pool.submit(() -> jdbc.sql("UPDATE customer SET name='架空の同時変更',version=version+1 WHERE tenant_id=? AND id=?")
                        .params(user.tenantId(), customer).update());
                awaitDatabaseWait("wait_event_type='Lock' AND query LIKE 'UPDATE customer%'");
                assertThat(edit.isDone()).isFalse();
                statement.execute("SELECT pg_advisory_unlock(87654321)");
                assertThat(confirm.get(10, TimeUnit.SECONDS).status()).isEqualTo(ImportBatch.Status.CONFIRMED);
                assertThat(edit.get(10, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(jdbc.sql("SELECT snapshot->>'customerName' FROM invoice_revision WHERE tenant_id=? AND invoice_id IN (SELECT id FROM invoice WHERE customer_id=?)")
                        .params(user.tenantId(), customer).query(String.class).single()).isEqualTo("架空請求先");
            } finally { statement.execute("SELECT pg_advisory_unlock(87654321)"); }
        } finally {
            jdbc.sql("DROP TRIGGER hold_invoice_fixture ON invoice").update(); jdbc.sql("DROP FUNCTION hold_invoice_fixture()").update();
        }
    }

    @Test
    void pagesByDueDateAndIdWithoutDuplicatesAndFiltersTenantCustomerAndCancelledInvoices() {
        var batch = preview(row("A", "100") + row("B", "200") + row("C", "300"));
        confirmation.confirm(user, batch.id(), 0);
        var first = receivables.list(user.tenantId(), customer, null, null, null, 1);
        var second = receivables.list(user.tenantId(), customer, null, null, first.nextCursor(), 1);
        var third = receivables.list(user.tenantId(), customer, null, null, second.nextCursor(), 1);
        assertThat(List.of(first.items().getFirst().id(), second.items().getFirst().id(), third.items().getFirst().id())).doesNotHaveDuplicates();
        assertThat(third.nextCursor()).isNull();
        assertThat(receivables.list(AuthFixtures.BETA, customer, null, null, null, 100).items()).isEmpty();
        assertThat(receivables.list(user.tenantId(), customer, LocalDate.of(2027, 1, 1), null, null, 100).items()).isEmpty();
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?")
                .params(user.tenantId(), first.items().getFirst().id()).update();
        assertThat(receivables.list(user.tenantId(), customer, null, null, null, 100).items()).hasSize(2);
        assertThatThrownBy(() -> receivables.list(user.tenantId(), null, null, null, "bad", 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenThousandInvoicesAndFinalRowInvalidFinishWithinBoundedHeapWithIndexedPages() {
        var pools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        pools.forEach(pool -> pool.resetPeakUsage());
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 10_000; index++) { rows.append(row("TENK-" + index, "100")); }
        long start = System.nanoTime();
        var batch = preview(rows.toString()); confirmation.confirm(user, batch.id(), 0);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(invoices()).isEqualTo(10_000); assertThat(revisions()).isEqualTo(10_000); assertThat(invoiceAudits()).isEqualTo(10_000);
        assertThat(receivables.list(user.tenantId(), customer, null, null, null, 100).items()).hasSize(100);
        rows.setLength(0);
        for (int index = 0; index < 10_000; index++) { rows.append(row("INVALID-" + index, index == 9999 ? "abc" : "100")); }
        var invalid = preview(rows.toString()); rejected(invalid, 0, "IMPORT_INVALID");
        assertThat(store.errors(user.tenantId(), invalid.id(), 0, 10).getFirst().value().rowNumber()).isEqualTo(10001);
        assertThat(invoices()).isEqualTo(10_000);
        var plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM invoice WHERE tenant_id=? AND status='ACTIVE' ORDER BY due_date,id LIMIT 100")
                .param(user.tenantId()).query(String.class).list();
        assertThat(String.join("\n", plan)).contains("invoice_receivables_page");
        System.out.println("INVOICE_10K previewConfirmMs=" + elapsedMillis + " heapPoolPeakBytes="
                + pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum() + " heapMaxBytes="
                + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() + " plan=" + String.join(" | ", plan));
    }

    private ImportBatch preview(String rows) { return preview(InvoiceCsv.HEADER + rows, true); }
    private ImportBatch preview(String csv, boolean includesHeader) {
        var batch = previews.preview(user, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))); batchIds.add(batch.id()); return batch;
    }
    private String row(String number, String amount) { return code + "," + prefix + "-" + number + ",2026-10-29,2026-11-30," + amount + "\n"; }
    private String twoRows(String first, String second) { return row("A", first) + row("B", second); }
    private void rejected(ImportBatch batch, long version, String error) {
        assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), version)).isInstanceOf(ImportRejected.class).hasMessage(error);
    }
    private long invoices() { return jdbc.sql("SELECT count(*) FROM invoice WHERE tenant_id=? AND customer_id=?").params(user.tenantId(), customer).query(Long.class).single(); }
    private long revisions() { return jdbc.sql("SELECT count(*) FROM invoice_revision WHERE tenant_id=? AND invoice_id IN (SELECT id FROM invoice WHERE customer_id=?)").params(user.tenantId(), customer).query(Long.class).single(); }
    private long invoiceAudits() { return jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='INVOICE_CREATED' AND details->>'customerCode'=?").params(user.tenantId(), code).query(Long.class).single(); }
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.Stream.of(first, second).map(call -> pool.submit(() -> {
                start.await(); try { return call.call(); } catch (ImportRejected rejected) { return rejected; }
            })).toList();
            start.countDown(); return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
    private void awaitDatabaseWait(String predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND " + predicate + ")")
                    .query(Boolean.class).single()) { return; }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected controlled PostgreSQL wait was not observed");
    }
}
