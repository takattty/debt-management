package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("auth-test")
class LedgerStorageTest {
    @Autowired JdbcClient jdbc;
    @Autowired LedgerRepository ledger;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    private TransactionTemplate transaction;
    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID foreignActor = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID secondPayment = UUID.randomUUID();
    private final UUID foreignPayment = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID foreignInvoice = UUID.randomUUID();

    @BeforeEach
    void dedicatedFictionalFacts() {
        transaction = new TransactionTemplate(transactions);
        for (UUID id : List.of(tenant, other)) {
            jdbc.sql("INSERT INTO tenant(id,name) VALUES (?,'Fictional ledger fixture')").param(id).update();
        }
        user(tenant, actor); user(other, foreignActor);
        UUID invoiceBatch = batch(tenant, actor, "INVOICE");
        UUID paymentBatch = batch(tenant, actor, "PAYMENT");
        UUID foreignInvoiceBatch = batch(other, foreignActor, "INVOICE");
        UUID foreignPaymentBatch = batch(other, foreignActor, "PAYMENT");
        invoice(tenant, first, customer(tenant), invoiceBatch, 100000);
        // Different customers demonstrate storage neutrality; no public B06 policy is being authorized.
        invoice(tenant, second, customer(tenant), invoiceBatch, 60000);
        invoice(other, foreignInvoice, customer(other), foreignInvoiceBatch, 100000);
        payment(tenant, payment, actor, paymentBatch); payment(tenant, secondPayment, actor, paymentBatch);
        payment(other, foreignPayment, foreignActor, foreignPaymentBatch);
    }

    @AfterEach
    void truncateOnlyDedicatedTestDatabaseImmutableFixtures() {
        jdbc.sql("TRUNCATE allocation_line,allocation_operation,reconciliation_payment_state,payment").update();
        for (UUID id : List.of(tenant, other)) {
            for (String table : List.of("invoice", "customer", "import_batch", "app_user", "tenant")) {
                jdbc.sql("DELETE FROM " + table + " WHERE " + (table.equals("tenant") ? "id" : "tenant_id") + "=?").param(id).update();
            }
        }
    }

    @Test
    void untouchedBalancesHaveZeroVersionAndSharedLocksRequireATransactionAndSeparateTenants() {
        assertThat(ledger.payment(tenant, payment).version()).isZero();
        assertThat(ledger.payment(tenant, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(120000));
        assertThat(count("reconciliation_payment_state")).isZero();
        assertThatThrownBy(() -> ledger.lockPositions(tenant, payment, List.of(first)))
                .isInstanceOf(IllegalTransactionStateException.class);
        transaction.executeWithoutResult(status -> {
            var locked = ledger.lockPositions(tenant, payment, List.of(second, first, second));
            assertThat(locked.invoices()).hasSize(2);
            assertThat(locked.payment().version()).isZero();
        });
        assertThatThrownBy(() -> ledger.payment(other, payment)).hasMessage("Payment not found");
        assertThatThrownBy(() -> ledger.invoice(other, first)).hasMessage("Invoice not found");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> ledger.lockPositions(tenant, foreignPayment, List.of(first))))
                .hasMessage("Payment not found");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> ledger.lockPositions(tenant, secondPayment, List.of(foreignInvoice))))
                .hasMessage("Invoice not found");
    }

    @Test
    void partialMultiInvoiceCashExplicitFeeOverpaymentAndWholeBatchReversalConserveFacts() {
        UUID original = confirm(payment, List.of(line(first, 60000, 0), line(second, 40000, 440)));
        assertThat(ledger.payment(tenant, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(20000));
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(40000));
        assertThat(ledger.invoice(tenant, second).balance().outstanding()).isEqualTo(BigInteger.valueOf(19560));
        confirm(secondPayment, List.of(line(first, 39560, 440)));
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isZero();
        reverse(payment, original);
        assertThat(ledger.payment(tenant, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(120000));
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(60000));
        assertThat(ledger.invoice(tenant, second).balance().outstanding()).isEqualTo(BigInteger.valueOf(60000));
        BigInteger unchangedCash = ledger.payment(tenant, secondPayment).balance().unallocated();
        confirm(secondPayment, List.of(line(second, 0, 60000)));
        assertThat(ledger.payment(tenant, secondPayment).balance().unallocated()).isEqualTo(unchangedCash);
        assertThat(ledger.invoice(tenant, second).balance().outstanding()).isZero();
        assertThat(count("allocation_operation")).isEqualTo(4);
        assertThat(count("allocation_line")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT amount_jpy FROM payment WHERE tenant_id=? AND id=?").params(tenant, payment).query(Long.class).single())
                .isEqualTo(120000);
    }

    @Test
    void deferredConservationRejectsWholeBatchAndExactNumericNeverWraps() {
        for (var lines : List.of(List.of(line(first, 100000, 0), line(second, 30000, 0)),
                List.of(line(first, 90000, 20000)), List.of(line(first, Long.MAX_VALUE, Long.MAX_VALUE)))) {
            assertThatThrownBy(() -> confirm(payment, lines)).isInstanceOf(DataAccessException.class);
            assertThat(count("allocation_operation")).isZero(); assertThat(count("allocation_line")).isZero();
            assertThat(ledger.payment(tenant, payment).version()).isZero();
        }
        for (String invalid : List.of("-1", "0.5", "NaN", "Infinity", "-Infinity")) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                UUID operation = header(payment, actor, UUID.randomUUID(), "CONFIRM", null);
                insertLine(operation, first, new BigDecimal(invalid.equals("NaN") || invalid.contains("Infinity") ? "1" : invalid), BigDecimal.ZERO);
                if (invalid.equals("NaN") || invalid.contains("Infinity")) {
                    jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,0,?::numeric,0)").params(tenant, operation, second, invalid).update();
                }
            })).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> confirm(payment, List.of(line(first, 0, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("allocation_operation")).isZero();
    }

    @Test
    void tenantActorReferencesDuplicateRequestsEmptyHeadersAndVersionGapsRejectAtomically() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> header(payment, foreignActor, UUID.randomUUID(), "CONFIRM", null)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            UUID operation = header(payment, actor, UUID.randomUUID(), "CONFIRM", null);
            insertLine(operation, foreignInvoice, BigDecimal.ONE, BigDecimal.ZERO);
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> header(payment, actor, UUID.randomUUID(), "CONFIRM", null)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            UUID operation = header(payment, actor, UUID.randomUUID(), "CONFIRM", null);
            insertLine(operation, first, BigDecimal.ONE, BigDecimal.ZERO);
            jdbc.sql("UPDATE reconciliation_payment_state SET version=2 WHERE tenant_id=? AND payment_id=?").params(tenant, payment).update();
        })).isInstanceOf(DataAccessException.class);
        UUID request = UUID.randomUUID();
        transaction.executeWithoutResult(status -> append(payment, request, List.of(line(first, 1, 0))));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> append(payment, request, List.of(line(second, 1, 0)))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("allocation_operation")).isEqualTo(1); assertThat(ledger.payment(tenant, payment).version()).isEqualTo(1);
    }

    @Test
    void historyCannotBeChangedExtendedOrReversedTwiceAndPaymentFactsRemainImmutable() {
        UUID original = confirm(payment, List.of(line(first, 100, 0)));
        for (String sql : List.of("UPDATE allocation_operation SET reason='changed' WHERE tenant_id=?",
                "DELETE FROM allocation_operation WHERE tenant_id=?", "UPDATE allocation_line SET cash_jpy=1 WHERE tenant_id=?",
                "DELETE FROM allocation_line WHERE tenant_id=?", "UPDATE payment SET amount_jpy=1 WHERE tenant_id=?", "DELETE FROM payment WHERE tenant_id=?",
                "UPDATE reconciliation_payment_state SET version=0 WHERE tenant_id=?", "DELETE FROM reconciliation_payment_state WHERE tenant_id=?")) {
            assertThatThrownBy(() -> jdbc.sql(sql).param(tenant).update()).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> insertLine(original, second, BigDecimal.ONE, BigDecimal.ZERO)).isInstanceOf(DataAccessException.class);
        UUID reversal = reverse(payment, original);
        assertThatThrownBy(() -> reverse(payment, original)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> reverse(payment, reversal)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> reverse(secondPayment, original)).isInstanceOf(DataAccessException.class);
        assertThat(count("allocation_operation")).isEqualTo(2); assertThat(count("allocation_line")).isEqualTo(1);
    }

    @Test
    void invoiceAmountAndStatusCannotBreakConservationAndAreReleasedAfterWholeReversal() {
        UUID original = confirm(payment, List.of(line(first, 60000, 440)));
        assertThatThrownBy(() -> jdbc.sql("UPDATE invoice SET amount_jpy=60000 WHERE tenant_id=? AND id=?").params(tenant, first).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?")
                .params(tenant, first).update()).isInstanceOf(DataAccessException.class);
        reverse(payment, original);
        jdbc.sql("UPDATE invoice SET amount_jpy=60000 WHERE tenant_id=? AND id=?").params(tenant, first).update();
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(60000));
    }

    @Test
    void staleRepeatableReadAndSerializableSnapshotsCannotMutateLedgerOrReduceOrCancelInvoices() throws Exception {
        transaction.executeWithoutResult(status -> ledger.lockPositions(tenant, payment, List.of()));
        for (int isolation : List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            UUID committedAllocation;
            try (var connection = dataSource.getConnection()) {
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(false);
                // Establish the old snapshot before a different payment commits its READ COMMITTED allocation.
                assertThat(activeCash(connection)).isZero();
                committedAllocation = confirm(secondPayment, List.of(line(first, 60000, 0)));
                assertThat(activeCash(connection)).isZero(); // Snapshot reads stay valid and unchanged.
                try (var state = connection.prepareStatement("SELECT version FROM reconciliation_payment_state WHERE tenant_id=? AND payment_id=? FOR UPDATE")) {
                    state.setObject(1, tenant); state.setObject(2, payment); state.executeQuery().close();
                }
                try (var invoice = connection.prepareStatement("SELECT id FROM invoice WHERE tenant_id=? AND id=? ORDER BY id FOR UPDATE")) {
                    invoice.setObject(1, tenant); invoice.setObject(2, first); invoice.executeQuery().close();
                }
                requireIsolationRejection(connection, "UPDATE reconciliation_payment_state SET version=version+1 WHERE tenant_id=? AND payment_id=?", tenant, payment);
                requireIsolationRejection(connection, """
                        INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,payment_version,actor_user_id,reason)
                        VALUES (?,?,?,?,'CONFIRM',1,?,'Fictional stale snapshot')
                        """, UUID.randomUUID(), tenant, payment, UUID.randomUUID(), actor);
                requireIsolationRejection(connection, "INSERT INTO allocation_line VALUES (?,?,?,0,60000,0)", tenant, committedAllocation, second);
                requireIsolationRejection(connection, "UPDATE invoice SET amount_jpy=50000 WHERE tenant_id=? AND id=?", tenant, first);
                requireIsolationRejection(connection, "UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?", tenant, first);
                connection.rollback();
            }
            assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(40000));
            assertThat(ledger.invoice(tenant, first).status()).isEqualTo("ACTIVE");
            assertThat(ledger.payment(tenant, payment).version()).isZero();
            reverse(secondPayment, committedAllocation);
            assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(100000));
        }
    }

    private BigDecimal activeCash(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(sum(l.cash_jpy),0) FROM allocation_line l
                JOIN allocation_operation o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
                WHERE l.tenant_id=? AND l.invoice_id=? AND o.kind='CONFIRM'
                AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
                """)) {
            statement.setObject(1, tenant); statement.setObject(2, first);
            try (var result = statement.executeQuery()) { result.next(); return result.getBigDecimal(1); }
        }
    }

    private void requireIsolationRejection(Connection connection, String sql, Object... parameters) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) { statement.setObject(index + 1, parameters[index]); }
            assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("25000"));
        } finally { connection.rollback(savepoint); }
    }

    @Test
    void concurrentPaymentsCannotOverallocateOneInvoiceAndOppositeInvoiceOrdersDoNotDeadlock() throws Exception {
        var outcomes = race(() -> confirm(payment, List.of(line(first, 60000, 0))), () -> confirm(secondPayment, List.of(line(first, 60000, 0))));
        assertThat(outcomes.stream().filter(UUID.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(DataAccessException.class::isInstance)).hasSize(1);
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(40000));
        UUID winner = (UUID) outcomes.stream().filter(UUID.class::isInstance).findFirst().orElseThrow();
        UUID winnerPayment = jdbc.sql("SELECT payment_id FROM allocation_operation WHERE tenant_id=? AND id=?").params(tenant, winner).query(UUID.class).single();
        reverse(winnerPayment, winner);
        var both = race(() -> confirm(payment, List.of(line(first, 30000, 0), line(second, 30000, 0))),
                () -> confirm(secondPayment, List.of(line(second, 30000, 0), line(first, 30000, 0))));
        assertThat(both).allMatch(UUID.class::isInstance);
        assertThat(ledger.invoice(tenant, first).balance().outstanding()).isEqualTo(BigInteger.valueOf(40000));
        assertThat(ledger.invoice(tenant, second).balance().outstanding()).isZero();
    }

    private LedgerBalances.Line line(UUID invoice, long cash, long fee) {
        return new LedgerBalances.Line(invoice, 0, BigInteger.valueOf(cash), BigInteger.valueOf(fee));
    }
    private UUID confirm(UUID sourcePayment, List<LedgerBalances.Line> lines) {
        return transaction.execute(status -> append(sourcePayment, UUID.randomUUID(), lines));
    }
    private UUID append(UUID sourcePayment, UUID request, List<LedgerBalances.Line> lines) {
        ledger.lockPositions(tenant, sourcePayment, lines.stream().map(LedgerBalances.Line::invoiceId).toList());
        UUID operation = header(sourcePayment, actor, request, "CONFIRM", null);
        for (var line : lines) { insertLine(operation, line.invoiceId(), new BigDecimal(line.cash()), new BigDecimal(line.fee())); }
        return operation;
    }
    private UUID header(UUID sourcePayment, UUID sourceActor, UUID request, String kind, UUID original) {
        var position = ledger.lockPositions(tenant, sourcePayment, List.of()).payment();
        UUID operation = UUID.randomUUID();
        jdbc.sql("UPDATE reconciliation_payment_state SET version=version+1 WHERE tenant_id=? AND payment_id=?").params(tenant, sourcePayment).update();
        jdbc.sql("""
                INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason)
                VALUES (?,?,?,?,?,?,?,?,'Fictional internal ledger fixture')
                """).params(operation, tenant, sourcePayment, request, kind, original, Math.addExact(position.version(), 1), sourceActor).update();
        return operation;
    }
    private void insertLine(UUID operation, UUID invoice, BigDecimal cash, BigDecimal fee) {
        jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,0,?,?)").params(tenant, operation, invoice, cash, fee).update();
    }
    private UUID reverse(UUID sourcePayment, UUID original) {
        return transaction.execute(status -> {
            var ids = jdbc.sql("SELECT invoice_id FROM allocation_line WHERE tenant_id=? AND operation_id=?").params(tenant, original).query(UUID.class).list();
            ledger.lockPositions(tenant, sourcePayment, ids);
            return header(sourcePayment, actor, UUID.randomUUID(), "REVERSE", original);
        });
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id=?").param(tenant).query(Long.class).single(); }
    private void user(UUID scope, UUID id) {
        jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,'$argon2id$sql-fixture','ADMIN')")
                .params(id, scope, id + "@example.test").update();
    }
    private UUID batch(UUID scope, UUID owner, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,version,created_by_user_id,
                    created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count)
                VALUES (?,?,?,1,decode(?,'hex'),'CONFIRMED',1,?,'2026-10-29T00:00:00Z','2026-10-30T00:00:00Z',?,'2026-10-29T00:00:00Z',1,0)
                """).params(id, scope, kind, "a".repeat(64), owner, owner).update();
        return id;
    }
    private UUID customer(UUID scope) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'Fictional ledger customer')").params(id, scope, id.toString()).update();
        return id;
    }
    private void invoice(UUID scope, UUID id, UUID customer, UUID batch, long amount) {
        jdbc.sql("INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id) VALUES (?,?,?,?,'2026-10-29','2026-11-30',?,?)")
                .params(id, scope, customer, id.toString(), amount, batch).update();
    }
    private void payment(UUID scope, UUID id, UUID owner, UUID batch) {
        jdbc.sql("INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) VALUES (?,?,?,'2026-10-29','Fictional payer',120000,?,?,CURRENT_TIMESTAMP)")
                .params(id, scope, id.toString(), batch, owner).update();
    }
    private List<Object> race(Callable<UUID> firstTask, Callable<UUID> secondTask) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(firstTask, secondTask).stream().map(task -> pool.submit(() -> {
                try {
                    return (Object) transaction.execute(status -> {
                        ready.countDown(); // Both real transactions hold a physical connection before either command starts.
                        try {
                            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Fixture start timed out"); }
                            return task.call();
                        } catch (RuntimeException failure) { throw failure;
                        } catch (Exception failure) { throw new IllegalStateException(failure); }
                    });
                } catch (DataAccessException failure) { return failure; }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown(); return List.of(tasks.getFirst().get(10, TimeUnit.SECONDS), tasks.getLast().get(10, TimeUnit.SECONDS));
        }
    }
}
