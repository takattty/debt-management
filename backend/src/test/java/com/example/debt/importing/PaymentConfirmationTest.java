package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.payment.PaymentsService;
import java.io.ByteArrayInputStream;
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
class PaymentConfirmationTest {
    @Autowired PaymentImportService previews;
    @Autowired ConfirmPaymentImport confirmation;
    @Autowired ConfirmCustomerImport customerConfirmation;
    @Autowired ConfirmInvoiceImport invoiceConfirmation;
    @Autowired PaymentsService payments;
    @Autowired UserAccounts users;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean ImportStore store;
    @MockitoBean(name = "importClock") Clock clock;
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private final List<UUID> batchIds = new ArrayList<>();
    private TenantUser user;

    @BeforeEach
    void fixedClock() {
        when(clock.instant()).thenReturn(NOW);
        user = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
    }

    @AfterEach
    void resetTestFactsUsingExplicitTestOnlyTruncate() {
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id IN (SELECT id FROM payment WHERE tenant_id=?)")
                .params(user.tenantId(), user.tenantId()).update();
        jdbc.sql("TRUNCATE payment").update();
        for (UUID id : batchIds) {
            jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND entity_id=?").params(user.tenantId(), id).update();
            for (String table : List.of("import_error", "import_row", "import_batch")) {
                jdbc.sql("DELETE FROM " + table + " WHERE tenant_id=? AND " + (table.equals("import_batch") ? "id" : "batch_id") + "=?")
                        .params(user.tenantId(), id).update();
            }
        }
    }

    @Test
    void confirmsEveryPaymentAndAuditOnceAndRecoversLostResponseAfterExpiry() {
        var batch = preview(row("A", "100") + row("B", "200"));
        assertThatThrownBy(() -> customerConfirmation.confirm(user, batch.id(), 0)).hasMessage("IMPORT_KIND_UNSUPPORTED");
        assertThatThrownBy(() -> invoiceConfirmation.confirm(user, batch.id(), 0)).hasMessage("IMPORT_KIND_UNSUPPORTED");
        var result = confirmation.confirm(user, batch.id(), 0);
        assertThat(result.status()).isEqualTo(ImportBatch.Status.CONFIRMED);
        assertThat(count("payment")).isEqualTo(2); assertThat(audits("PAYMENT_CREATED")).isEqualTo(2);
        assertThat(audits("PAYMENT_IMPORT_CONFIRMED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT created_by_user_id FROM payment WHERE tenant_id=?")
                .param(user.tenantId()).query(UUID.class).list()).containsOnly(user.userId());
        when(clock.instant()).thenReturn(batch.expiresAt());
        assertThat(confirmation.confirm(user, batch.id(), 0)).isEqualTo(result);
        assertThat(confirmation.confirm(user, batch.id(), 1)).isEqualTo(result);
        assertThat(count("payment")).isEqualTo(2);
    }

    @Test
    void invalidExpiredAndStaleVersionWriteNothingAndDoNotReserveContent() {
        var invalid = preview(row("A", "100") + row("B", "0"));
        rejected(invalid, 0, "IMPORT_INVALID");
        var valid = preview(row("A", "100") + row("B", "200"));
        rejected(valid, 1, "VERSION_CONFLICT");
        when(clock.instant()).thenReturn(valid.expiresAt()); rejected(valid, 0, "IMPORT_EXPIRED");
        assertThat(count("payment")).isZero(); assertThat(audits("PAYMENT_CREATED")).isZero();
        when(clock.instant()).thenReturn(NOW);
        var repaired = preview(row("A", "100") + row("B", "200"));
        assertThat(confirmation.confirm(user, repaired.id(), 0).status()).isEqualTo(ImportBatch.Status.CONFIRMED);
    }

    @Test
    void conflictingTransactionIdInvalidatesWholeBatchAfterRollbackAndKeepsPhysicalRow() {
        var winner = preview(row("A", "100"));
        var loser = preview(row("NEW", "200") + row("A", "101"));
        var snapshot = store.paymentRows(user.tenantId(), loser.id(), 0, 100);
        confirmation.confirm(user, winner.id(), 0); rejected(loser, 0, "IMPORT_REFERENCE_CONFLICT");
        assertThat(count("payment")).isEqualTo(1); assertThat(audits("PAYMENT_CREATED")).isEqualTo(1);
        assertThat(store.get(user.tenantId(), loser.id(), false).version()).isEqualTo(1);
        assertThat(store.paymentRows(user.tenantId(), loser.id(), 0, 100)).isEqualTo(snapshot);
        assertThat(store.errors(user.tenantId(), loser.id(), 0, 10).getFirst().value().rowNumber()).isEqualTo(3);
    }

    @Test
    void sameBatchRaceIsIdempotentAndOppositeOrderSemanticRaceReturnsOriginalBatch() throws Exception {
        var batch = preview(row("A", "100") + row("B", "200"));
        var same = race(() -> confirmation.confirm(user, batch.id(), 0), () -> confirmation.confirm(user, batch.id(), 0));
        assertThat(same).allMatch(ImportBatch.class::isInstance); assertThat(same.getFirst()).isEqualTo(same.getLast());
        var first = preview(row("C", "100") + row("D", "200"));
        var second = preview(row("D", "200") + row("C", "100"));
        assertThat(first.semanticFingerprint()).isEqualTo(second.semanticFingerprint());
        jdbc.sql("CREATE FUNCTION slow_payment_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN PERFORM pg_sleep(0.1); RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER slow_payment_fixture BEFORE INSERT ON payment FOR EACH ROW EXECUTE FUNCTION slow_payment_fixture()").update();
        try {
            var result = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
            assertThat(result.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
            assertThat(result.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast)).singleElement()
                    .extracting(ImportRejected::code).isEqualTo("IMPORT_ALREADY_CONFIRMED");
        } finally { jdbc.sql("DROP TRIGGER slow_payment_fixture ON payment").update(); jdbc.sql("DROP FUNCTION slow_payment_fixture()").update(); }
        assertThat(count("payment")).isEqualTo(4); assertThat(audits("PAYMENT_CREATED")).isEqualTo(4);
    }

    @Test
    void overlappingDifferentContentKeepsOneWholeBatchAndLateSemanticCommitIsRecognized() throws Exception {
        var first = preview(row("A", "100") + row("B", "200"));
        var second = preview(row("B", "201") + row("A", "101"));
        var result = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
        assertThat(result.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
        assertThat(result.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast)).singleElement()
                .extracting(ImportRejected::code).isEqualTo("IMPORT_REFERENCE_CONFLICT");
        assertThat(count("payment")).isEqualTo(2);
        var third = preview(row("C", "300"));
        var fourth = preview(row("C", "300"));
        confirmation.confirm(user, third.id(), 0);
        doReturn(null, third.id()).when(store).confirmedDuplicate(fourth);
        assertThatThrownBy(() -> confirmation.confirm(user, fourth.id(), 0)).isInstanceOfSatisfying(ImportRejected.class, failure -> {
            assertThat(failure.code()).isEqualTo("IMPORT_ALREADY_CONFIRMED"); assertThat(failure.originalBatchId()).isEqualTo(third.id());
        });
        assertThat(count("payment")).isEqualTo(3);
    }

    @Test
    void finalAuditFailureAndMidTransactionExpiryRollBackEveryFactAndAudit() {
        var batch = preview(row("A", "100") + row("B", "200"));
        jdbc.sql("CREATE FUNCTION reject_payment_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.action='PAYMENT_IMPORT_CONFIRMED' THEN RAISE EXCEPTION 'Fictional rejection'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_payment_audit_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_payment_audit_fixture()").update();
        try {
            assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), 0)).isInstanceOf(DataAccessException.class);
            assertThat(count("payment")).isZero(); assertThat(audits("PAYMENT_CREATED")).isZero();
            assertThat(store.get(user.tenantId(), batch.id(), false).version()).isZero();
        } finally { jdbc.sql("DROP TRIGGER reject_payment_audit_fixture ON audit_event").update(); jdbc.sql("DROP FUNCTION reject_payment_audit_fixture()").update(); }
        when(clock.instant()).thenReturn(NOW, NOW, batch.expiresAt()); rejected(batch, 0, "IMPORT_EXPIRED");
        assertThat(count("payment")).isZero(); assertThat(audits("PAYMENT_CREATED")).isZero();
        when(clock.instant()).thenReturn(NOW);
        assertThat(confirmation.confirm(user, batch.id(), 0).status()).isEqualTo(ImportBatch.Status.CONFIRMED);
    }

    @Test
    void boundedKeysetPagesUseTransferDateThenIdAndInclusiveDateFilters() {
        var batch = preview(row("A", "100") + row("B", "200") + "C,2026-10-31,架空,300\n");
        confirmation.confirm(user, batch.id(), 0);
        var first = payments.list(user.tenantId(), null, null, null, 1);
        var second = payments.list(user.tenantId(), null, null, first.nextCursor(), 1);
        var third = payments.list(user.tenantId(), null, null, second.nextCursor(), 1);
        assertThat(List.of(first.items().getFirst().id(), second.items().getFirst().id(), third.items().getFirst().id())).doesNotHaveDuplicates();
        assertThat(third.nextCursor()).isNull();
        assertThat(payments.list(user.tenantId(), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31), null, 100).items()).hasSize(1);
        assertThat(payments.list(AuthFixtures.BETA, null, null, first.nextCursor(), 100).items()).isEmpty();
        assertThatThrownBy(() -> payments.list(user.tenantId(), null, null, "bad", 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenThousandReceiptsAndLastInvalidRowRemainAtomicAndPageUsesIndex() {
        var rows = new StringBuilder();
        for (int index = 0; index < 10000; index++) { rows.append(row("TENK-" + index, "100")); }
        long start = System.nanoTime();
        confirmation.confirm(user, preview(rows.toString()).id(), 0);
        assertThat(count("payment")).isEqualTo(10000); assertThat(audits("PAYMENT_CREATED")).isEqualTo(10000);
        rows.setLength(0);
        for (int index = 0; index < 10000; index++) { rows.append(row("INVALID-" + index, index == 9999 ? "0" : "100")); }
        var invalid = preview(rows.toString()); rejected(invalid, 0, "IMPORT_INVALID");
        assertThat(store.errors(user.tenantId(), invalid.id(), 0, 1).getFirst().value().rowNumber()).isEqualTo(10001);
        assertThat(count("payment")).isEqualTo(10000);
        jdbc.sql("ANALYZE payment").update();
        var plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM payment WHERE tenant_id=? ORDER BY transfer_date,id LIMIT 100")
                .param(user.tenantId()).query(String.class).list();
        assertThat(String.join("\n", plan)).contains("Index Scan using payment_page").doesNotContain("Sort");
        System.out.println("PAYMENT_10K elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " plan=" + String.join(" | ", plan));
    }

    private ImportBatch preview(String rows) {
        var batch = previews.preview(user, new ByteArrayInputStream((PaymentCsv.HEADER + rows).getBytes(StandardCharsets.UTF_8)));
        batchIds.add(batch.id()); return batch;
    }
    private String row(String id, String amount) { return id + ",2026-10-30,架空," + amount + "\n"; }
    private void rejected(ImportBatch batch, long version, String code) {
        assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), version)).isInstanceOf(ImportRejected.class).hasMessage(code);
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id=?").param(user.tenantId()).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action=?").params(user.tenantId(), action).query(Long.class).single(); }
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.Stream.of(first, second).map(call -> pool.submit(() -> {
                start.await(); try { return call.call(); } catch (ImportRejected rejected) { return rejected; }
            })).toList();
            start.countDown(); return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        }
    }
}
