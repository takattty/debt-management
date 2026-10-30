package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.invoice.InvoiceConflict;
import com.example.debt.invoice.InvoiceMutationEligibility;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;

class LedgerInvoiceMutationTest extends LedgerPublicFixtures {
    @Autowired InvoiceMutationEligibility eligibility;

    @Test
    void oneYenCashOrExplicitFeeAndFullAllocationsRejectBothMutationApisWithoutAnyWrites() throws Exception {
        assertThatThrownBy(() -> eligibility.requireUnallocated(tenant, invoice)).isInstanceOf(IllegalTransactionStateException.class);
        for (long[] amounts : List.of(new long[] {1, 0}, new long[] {0, 1}, new long[] {60000, 440}, new long[] {100000, 0})) {
            UUID original = allocate(payment, invoice, amounts[0], amounts[1]);
            assertDeniedApis();
            reverse(payment, invoice, original);
        }
        correct(version(), 110000);
        assertThat(invoices.view(tenant, invoice).outstanding()).isEqualTo(110000);
        assertThat(invoices.history(tenant, invoice, -1, 100).items()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM allocation_line WHERE tenant_id=?").param(tenant).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void reversingOnlyOneOfMultipleOperationsStillBlocksUntilEveryCashAndFeeIsReversed() throws Exception {
        UUID first = allocate(payment, invoice, 50000, 0);
        UUID second = allocate(payment, invoice, 0, 1);
        reverse(payment, invoice, first);
        assertDeniedApis();
        reverse(payment, invoice, second);
        correct(version(), 110000);
        mutations.cancel(user, invoice, version(), "Fictional cancelled after reversals");
        var view = invoices.view(tenant, invoice);
        assertThat(view.invoice().amount()).isEqualTo(110000); assertThat(view.outstanding()).isZero();
        assertThat(invoices.history(tenant, invoice, -1, 100).items()).hasSize(3);
        assertThat(invoices.history(tenant, invoice, -1, 100).items().getFirst().snapshot().amount()).isEqualTo(100000);
        assertThat(ledger.payment(tenant, payment).balance().unallocated().longValueExact()).isEqualTo(120000);
    }

    @Test
    void waitedForConfirmIsVisibleToFreshGuardEvenWhenInternalWriterLeavesInvoiceVersionUnchanged() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            UUID target = invoice;
            Object outcome = waitBehindLockedWriter(() -> {
                var locked = ledger.lockPositions(tenant, payment, List.of(target));
                UUID original = header(payment, locked.payment().version() + 1, "CONFIRM", null);
                jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,0,0,1)").params(tenant, original, target).update();
                return null;
            }, () -> {
                if (cancel) { mutations.cancel(user, target, 0, "Fictional concurrent cancellation"); }
                else { correct(0, 200000); }
                return null;
            });
            assertThat(outcome).isInstanceOf(InvoiceConflict.class);
            assertThat(((InvoiceConflict) outcome).code()).isEqualTo("INVOICE_ALLOCATED");
            assertThat(version()).isZero();
            assertThat(invoices.view(tenant, invoice).outstanding()).isEqualTo(99999);
            // Leave the immutable first target intact; the second iteration uses another original.
            if (!cancel) { invoice = invoice(user, 100000); }
        }
    }

    @Test
    void waitedForWholeReversalAllowsCorrectionAndStaleInvoiceVersionStillRejects() throws Exception {
        UUID original = allocate(payment, invoice, 1, 1);
        Object outcome = waitBehindLockedWriter(() -> {
            var locked = ledger.lockPositions(tenant, payment, List.of(invoice));
            header(payment, locked.payment().version() + 1, "REVERSE", original);
            return null;
        }, () -> { correct(1, 110000); return "corrected"; });
        assertThat(outcome).isEqualTo("corrected");
        assertThat(version()).isEqualTo(2);
        assertThat(invoices.view(tenant, invoice).outstanding()).isEqualTo(110000);
        String before = trace();
        assertThatThrownBy(() -> mutations.cancel(user, invoice, 1, "Fictional stale cancellation"))
                .isInstanceOfSatisfying(InvoiceConflict.class, conflict -> assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT"));
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void cancellationWinningInvoiceLockMakesWaitingAllocationRollBackCompletely() throws Exception {
        Object outcome = waitBehindLockedWriter(() -> {
            mutations.cancel(user, invoice, 0, "Fictional winning cancellation");
            return null;
        }, () -> allocate(payment, invoice, 1, 0));
        assertThat(outcome).isInstanceOf(DataAccessException.class);
        assertThat(invoices.view(tenant, invoice).outstanding()).isZero();
        assertThat(version()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM allocation_operation WHERE tenant_id=?").param(tenant).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_payment_state WHERE tenant_id=?").param(tenant).query(Long.class).single()).isZero();
    }

    @Test
    void realAuditFailureAfterWholeReversalRollsBackCorrectionAndCancellationButKeepsImmutableLedger() {
        UUID original = allocate(payment, invoice, 1, 1);
        reverse(payment, invoice, original);
        String before = trace();
        jdbc.sql("""
                CREATE FUNCTION ledger_public_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.entity_id='%s'
                THEN RAISE EXCEPTION 'Fictional audit fault'; END IF; RETURN NEW; END; $$
                """.formatted(invoice)).update();
        jdbc.sql("CREATE TRIGGER ledger_public_audit_fault BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION ledger_public_audit_fault()").update();
        try {
            assertThatThrownBy(() -> correct(2, 110000)).isInstanceOf(DataAccessException.class);
            assertThat(trace()).isEqualTo(before);
            assertThatThrownBy(() -> mutations.cancel(user, invoice, 2, "Fictional failed cancellation")).isInstanceOf(DataAccessException.class);
            assertThat(trace()).isEqualTo(before);
        } finally {
            jdbc.sql("DROP TRIGGER ledger_public_audit_fault ON audit_event").update();
            jdbc.sql("DROP FUNCTION ledger_public_audit_fault()").update();
        }
    }

    private void assertDeniedApis() throws Exception {
        String before = trace();
        String correction = "{\"version\":" + version() + ",\"customerId\":\"" + customer
                + "\",\"issueDate\":\"0001-01-01\",\"dueDate\":\"9999-12-31\",\"amount\":200000,\"reason\":\"Fictional correction\"}";
        mvc.perform(put("/api/invoices/" + invoice).with(auth(user)).with(csrf()).contentType("application/json").content(correction))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALLOCATED"));
        String dateOnly = "{\"version\":" + version() + ",\"customerId\":\"" + customer
                + "\",\"issueDate\":\"0001-01-02\",\"dueDate\":\"9999-12-31\",\"amount\":100000,\"reason\":\"Fictional date-only correction\"}";
        mvc.perform(put("/api/invoices/" + invoice).with(auth(user)).with(csrf()).contentType("application/json").content(dateOnly))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALLOCATED"));
        mvc.perform(post("/api/invoices/" + invoice + "/cancel").with(auth(user)).with(csrf()).contentType("application/json")
                .content("{\"version\":" + version() + ",\"reason\":\"Fictional cancellation\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICE_ALLOCATED"));
        assertThat(trace()).isEqualTo(before);
    }

    private Object waitBehindLockedWriter(Callable<?> write, Callable<?> mutation) throws Exception {
        CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> tx().execute(status -> {
                try {
                    write.call(); locked.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return null;
                } catch (Exception error) { throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var waiter = executor.submit(() -> {
                try {
                    return tx().execute(status -> {
                        waiterPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        try { return mutation.call(); }
                        catch (RuntimeException error) { throw error; }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    });
                } catch (RuntimeException error) { return error; }
            });
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                boolean waiting;
                do {
                    waiting = jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')")
                            .param(waiterPid.get()).query(Boolean.class).single();
                    if (!waiting) { Thread.sleep(10); }
                } while (!waiting && System.nanoTime() < deadline);
                assertThat(waiting).as("second transaction actually waited for the same invoice row").isTrue();
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
            return waiter.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
}
