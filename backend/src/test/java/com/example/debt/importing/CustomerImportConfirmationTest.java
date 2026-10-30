package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

@SpringBootTest
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class CustomerImportConfirmationTest {
    @Autowired CustomerImportService previews;
    @Autowired ConfirmCustomerImport confirmation;
    @Autowired ImportStore store;
    @Autowired UserAccounts users;
    @Autowired JdbcClient jdbc;
    @MockitoBean(name = "importClock") Clock clock;
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private final String prefix = "T-" + UUID.randomUUID();
    private TenantUser user;

    @BeforeEach
    void fixedTimeAndPersistedActor() {
        when(clock.instant()).thenReturn(NOW);
        user = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
    }

    @AfterEach
    void removeOwnBusinessRowsAndImportFixtures() {
        jdbc.sql("DELETE FROM audit_event WHERE tenant_id=? AND (entity_id IN (SELECT id FROM customer WHERE code LIKE ?) OR action='CUSTOMER_IMPORT_CONFIRMED')")
                .params(AuthFixtures.ALPHA, prefix + "%").update();
        jdbc.sql("DELETE FROM customer WHERE tenant_id=? AND code LIKE ?").params(AuthFixtures.ALPHA, prefix + "%").update();
        for (String table : List.of("import_error", "import_row", "import_batch")) {
            jdbc.sql("DELETE FROM " + table + " WHERE tenant_id IN (?,?)").params(AuthFixtures.ALPHA, AuthFixtures.BETA).update();
        }
    }

    @Test
    void confirmsAllCustomersAndAuditsOnceAndReturnsSameResultForLostResponseRetryAfterExpiry() {
        ImportBatch batch = preview(twoRows("A", "B"));
        ImportBatch result = confirmation.confirm(user, batch.id(), 0);
        assertThat(result.status()).isEqualTo(ImportBatch.Status.CONFIRMED);
        assertThat(result.version()).isEqualTo(1);
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action='CUSTOMER_IMPORT_CONFIRMED'")
                .params(user.tenantId(), batch.id()).query(Long.class).single()).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
        assertThat(confirmation.confirm(user, batch.id(), 0)).isEqualTo(result);
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
    }

    @Test
    void invalidExpiredAndStaleVersionNeverWriteBusinessRows() {
        ImportBatch invalid = preview(CustomerCsv.HEADER + prefix + ",\n");
        assertRejected(invalid, 0, "IMPORT_INVALID");
        ImportBatch valid = preview(twoRows("A", "B"));
        assertRejected(valid, 1, "VERSION_CONFLICT");
        when(clock.instant()).thenReturn(valid.expiresAt());
        assertRejected(valid, 0, "IMPORT_EXPIRED");
        assertThat(customers()).isZero();
        assertThat(customerAudits()).isZero();
    }

    @Test
    void sameBatchConcurrentConfirmationIsIdempotentWithTwoRealConnections() throws Exception {
        var batch = preview(twoRows("A", "B"));
        var results = race(() -> confirmation.confirm(user, batch.id(), 0), () -> confirmation.confirm(user, batch.id(), 0));
        assertThat(results).allMatch(ImportBatch.class::isInstance);
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
    }

    @Test
    void equivalentDifferentBatchesRaceHasOneWinnerAndOriginalResultAndRecordedConflict() throws Exception {
        var first = preview(twoRows("A", "B"));
        var second = preview("\ufeffcustomer_code,customer_name\n" + prefix + "-2,B\n" + prefix + "-1,A\n");
        assertThat(first.semanticFingerprint()).isEqualTo(second.semanticFingerprint());
        var results = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
        assertThat(results.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
        ImportBatch winner = results.stream().filter(ImportBatch.class::isInstance).map(ImportBatch.class::cast).findFirst().orElseThrow();
        ImportRejected rejected = results.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast).findFirst().orElseThrow();
        assertThat(rejected.code()).isEqualTo("IMPORT_ALREADY_CONFIRMED");
        assertThat(rejected.originalBatchId()).isEqualTo(winner.id());
        ImportBatch loser = store.get(user.tenantId(), winner.id().equals(first.id()) ? second.id() : first.id(), false);
        assertThat(loser.status()).isEqualTo(ImportBatch.Status.PREVIEW_INVALID);
        assertThat(store.errors(user.tenantId(), loser.id(), 0, 10).getFirst().value().reasonCode()).isEqualTo("IMPORT_ALREADY_CONFIRMED");
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
    }

    @Test
    void differentContentWithSameCustomerCodesStillCreatesOneWholeBatchOnly() throws Exception {
        var first = preview(twoRows("A", "B"));
        var second = preview(twoRows("Changed A", "Changed B"));
        var results = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
        assertThat(results.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast))
                .singleElement().extracting(ImportRejected::code).isEqualTo("IMPORT_REFERENCE_CONFLICT");
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
    }

    @Test
    void reversedEquivalentBatchesWithOverlappingInsertsReturnOneResultWithoutDeadlock() throws Exception {
        reversedInsertRace(false);
    }

    @Test
    void reversedDifferentContentWithOverlappingInsertsReturnsConflictWithoutDeadlock() throws Exception {
        reversedInsertRace(true);
    }

    private void reversedInsertRace(boolean differentContent) throws Exception {
        var first = preview(twoRows("A", "B"));
        var second = preview(CustomerCsv.HEADER + prefix + "-2," + (differentContent ? "Changed B" : "B")
                + "\n" + prefix + "-1," + (differentContent ? "Changed A" : "A") + "\n");
        var firstSnapshot = store.rows(user.tenantId(), first.id(), 0, 10);
        var secondSnapshot = store.rows(user.tenantId(), second.id(), 0, 10);
        // Both transactions reach their first unique-index insert before continuing to the second.
        // Without deterministic write ordering, reversed CSV records create a real PostgreSQL lock cycle.
        jdbc.sql("""
                CREATE FUNCTION overlap_customer_import_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(1); RETURN NEW; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER overlap_customer_import_fixture BEFORE INSERT ON customer FOR EACH ROW EXECUTE FUNCTION overlap_customer_import_fixture()")
                .update();
        List<Object> results;
        try {
            results = race(() -> confirmation.confirm(user, first.id(), 0), () -> confirmation.confirm(user, second.id(), 0));
        } finally {
            jdbc.sql("DROP TRIGGER overlap_customer_import_fixture ON customer").update();
            jdbc.sql("DROP FUNCTION overlap_customer_import_fixture()").update();
        }
        assertThat(results.stream().filter(ImportBatch.class::isInstance)).hasSize(1);
        var winner = results.stream().filter(ImportBatch.class::isInstance).map(ImportBatch.class::cast).findFirst().orElseThrow();
        var rejected = results.stream().filter(ImportRejected.class::isInstance).map(ImportRejected.class::cast).findFirst().orElseThrow();
        assertThat(rejected.code()).isEqualTo(differentContent ? "IMPORT_REFERENCE_CONFLICT" : "IMPORT_ALREADY_CONFIRMED");
        assertThat(rejected.originalBatchId()).isEqualTo(differentContent ? null : winner.id());
        var loser = store.get(user.tenantId(), winner.id().equals(first.id()) ? second.id() : first.id(), false);
        assertThat(loser.status()).isEqualTo(ImportBatch.Status.PREVIEW_INVALID);
        assertThat(loser.errorCount()).isGreaterThan(0);
        assertThat(store.rows(user.tenantId(), first.id(), 0, 10)).isEqualTo(firstSnapshot);
        assertThat(store.rows(user.tenantId(), second.id(), 0, 10)).isEqualTo(secondSnapshot);
        assertThat(firstSnapshot).extracting(CustomerCsv.Row::customerCode).containsExactly(prefix + "-1", prefix + "-2");
        assertThat(secondSnapshot).extracting(CustomerCsv.Row::customerCode).containsExactly(prefix + "-2", prefix + "-1");
        assertThat(customers()).isEqualTo(2);
        assertThat(customerAudits()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id IN (?,?) AND action='CUSTOMER_IMPORT_CONFIRMED'")
                .params(user.tenantId(), first.id(), second.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void newCustomerBetweenPreviewAndConfirmInvalidatesPreviewAndPreservesSnapshotAfterRollback() {
        var batch = preview(twoRows("A", "B"));
        var snapshot = store.rows(user.tenantId(), batch.id(), 0, 10);
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'Fictional manual registration')")
                .params(UUID.randomUUID(), user.tenantId(), prefix + "-1").update();
        assertRejected(batch, 0, "IMPORT_REFERENCE_CONFLICT");
        ImportBatch invalid = store.get(user.tenantId(), batch.id(), false);
        assertThat(invalid.status()).isEqualTo(ImportBatch.Status.PREVIEW_INVALID);
        assertThat(invalid.version()).isEqualTo(1);
        assertThat(store.rows(user.tenantId(), batch.id(), 0, 10)).isEqualTo(snapshot);
        assertThat(store.errors(user.tenantId(), batch.id(), 0, 10).getFirst().value().rowNumber()).isEqualTo(2);
        assertThat(customers()).isEqualTo(1);
        assertThat(customerAudits()).isZero();
    }

    @Test
    void auditInsertFailureRollsBackCustomersConfirmationAndEveryAudit() {
        var batch = preview(twoRows("A", "B"));
        jdbc.sql("""
                CREATE FUNCTION reject_import_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.action='CUSTOMER_IMPORT_CONFIRMED' THEN RAISE EXCEPTION 'Fictional audit rejection'; END IF; RETURN NEW; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER reject_import_audit_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_import_audit_fixture()")
                .update();
        try {
            assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), 0)).isInstanceOf(DataAccessException.class);
            assertThat(customers()).isZero();
            assertThat(customerAudits()).isZero();
            assertThat(store.get(user.tenantId(), batch.id(), false).status()).isEqualTo(ImportBatch.Status.PREVIEW_VALID);
            assertThat(store.get(user.tenantId(), batch.id(), false).version()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER reject_import_audit_fixture ON audit_event").update();
            jdbc.sql("DROP FUNCTION reject_import_audit_fixture()").update();
        }
        assertThat(confirmation.confirm(user, batch.id(), 0).status()).isEqualTo(ImportBatch.Status.CONFIRMED);
    }

    @Test
    void expiryDuringBusinessInsertionRollsBackTheEntireTransaction() {
        var batch = preview(twoRows("A", "B"));
        when(clock.instant()).thenReturn(NOW, batch.expiresAt());
        assertRejected(batch, 0, "IMPORT_EXPIRED");
        assertThat(customers()).isZero();
        assertThat(customerAudits()).isZero();
        assertThat(store.get(user.tenantId(), batch.id(), false).status()).isEqualTo(ImportBatch.Status.PREVIEW_VALID);
    }

    private ImportBatch preview(String csv) {
        return previews.preview(user, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
    private String twoRows(String first, String second) {
        return CustomerCsv.HEADER + prefix + "-1," + first + "\n" + prefix + "-2," + second + "\n";
    }
    private void assertRejected(ImportBatch batch, long version, String code) {
        assertThatThrownBy(() -> confirmation.confirm(user, batch.id(), version)).isInstanceOf(ImportRejected.class).hasMessage(code);
    }
    private long customers() {
        return jdbc.sql("SELECT count(*) FROM customer WHERE tenant_id=? AND code LIKE ?").params(user.tenantId(), prefix + "%").query(Long.class).single();
    }
    private long customerAudits() {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action='CUSTOMER_CREATED' AND details->>'code' LIKE ?")
                .params(user.tenantId(), prefix + "%").query(Long.class).single();
    }
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = java.util.stream.Stream.of(first, second).map(call -> pool.submit(() -> {
                start.await(); try { return call.call(); } catch (ImportRejected rejected) { return rejected; }
            })).toList();
            start.countDown();
            return List.of(results.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS), results.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
