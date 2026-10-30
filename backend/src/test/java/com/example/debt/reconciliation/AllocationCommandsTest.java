package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;

class AllocationCommandsTest extends LedgerPublicFixtures {
    @Autowired AllocationCommands commands;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void crossCustomerCashAndExplicitFeeAreAtomicAndReversalThenReconfirmRestoresConservation() {
        UUID second = invoice(user, 50000);
        UUID secondCustomer = customer(tenant);
        jdbc.sql("UPDATE invoice SET customer_id=? WHERE tenant_id=? AND id=?").params(secondCustomer, tenant, second).update();
        List<AllocationCommands.Line> lines = List.of(line(invoice, 0, 99560, 440), line(second, 0, 20000, 0));
        UUID request = UUID.randomUUID();
        var confirmed = confirm(request, 0, lines);
        assertThat(ledger.payment(tenant, payment).balance().unallocated().longValueExact()).isEqualTo(440);
        assertThat(ledger.invoice(tenant, invoice).balance().outstanding().signum()).isZero();
        assertThat(ledger.invoice(tenant, second).balance().outstanding().longValueExact()).isEqualTo(30000);
        assertThat(version()).isEqualTo(1);
        assertThat(confirm(request, 0, List.of(lines.getLast(), lines.getFirst()))).isEqualTo(confirmed);
        var reversed = commands.reverse(user, payment, confirmed.id(), UUID.randomUUID(), 1, "Fictional fee correction",
                List.of(line(second, 1, 0, 0), line(invoice, 1, 0, 0)));
        assertThat(reversed.paymentVersion()).isEqualTo(2);
        assertThat(ledger.payment(tenant, payment).balance().unallocated().longValueExact()).isEqualTo(120000);
        assertThat(ledger.invoice(tenant, invoice).balance().outstanding().longValueExact()).isEqualTo(100000);
        assertThat(confirm(request, 0, lines)).isEqualTo(confirmed);
        confirm(UUID.randomUUID(), 2, List.of(line(invoice, 2, 99900, 100)));
        assertThat(ledger.invoice(tenant, invoice).balance().outstanding().signum()).isZero();
        assertThat(jdbc.sql("SELECT amount_jpy FROM payment WHERE tenant_id=? AND id=?").params(tenant, payment).query(Long.class).single()).isEqualTo(120000);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND action LIKE 'ALLOCATION_%'")
                .param(tenant).query(Long.class).single()).isEqualTo(3);
        assertThat(invoices.history(tenant, invoice, -1, 100).items()).hasSize(1);
    }

    @Test
    void feeOnlyStandaloneDuplicateEmptyOverpaymentStaleForeignAndVersionOverflowLeaveNoWrites() {
        String before = trace();
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 0, 440)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 0, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 1, 0), line(invoice, 0, 1, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 100000, 1))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("BALANCE_CONFLICT");
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 1, List.of(line(invoice, 0, 1, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 1, 1, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("VERSION_CONFLICT");
        UUID foreignInvoice = invoice(foreign, 100);
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 1, 0), line(foreignInvoice, 0, 1, 0))))
                .isInstanceOf(AllocationCommands.Missing.class);
        assertThat(trace()).isEqualTo(before);
        jdbc.sql("UPDATE invoice SET version=? WHERE tenant_id=? AND id=?").params(Long.MAX_VALUE, tenant, invoice).update();
        before = trace();
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 0, List.of(line(invoice, Long.MAX_VALUE, 1, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("VERSION_EXHAUSTED");
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void sumsAboveTransportLimitNeverWrapAndPaymentOverAllocationRejectsWholeBatch() {
        long maximum = 9007199254740991L;
        UUID first = invoice(user, maximum); UUID second = invoice(user, maximum);
        UUID receipt = payment(user, maximum);
        String before = trace();
        assertThatThrownBy(() -> commands.confirm(user, receipt, UUID.randomUUID(), 0, "Fictional oversized sum",
                List.of(line(first, 0, maximum, 0), line(second, 0, maximum, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("BALANCE_CONFLICT");
        assertThat(trace()).isEqualTo(before);
        var result = commands.confirm(user, receipt, UUID.randomUUID(), 0, "Fictional maximum", List.of(line(first, 0, maximum, 0)));
        assertThat(result.paymentVersion()).isEqualTo(1);
        assertThat(ledger.payment(tenant, receipt).balance().unallocated().signum()).isZero();
    }

    @Test
    void requestIdConflictsAcrossPayloadPathAndKindAndReversalIsWholeOperationExactlyOnce() {
        UUID request = UUID.randomUUID(); var original = confirm(request, 0, List.of(line(invoice, 0, 10000, 440)));
        String before = trace();
        assertThatThrownBy(() -> confirm(request, 0, List.of(line(invoice, 0, 10001, 440))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> commands.reverse(user, payment, original.id(), request, 1, "Fictional test", List.of(line(invoice, 1, 0, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("IDEMPOTENCY_CONFLICT");
        UUID another = payment(user, 10);
        String withAnother = trace();
        assertThatThrownBy(() -> commands.confirm(user, another, request, 0, "Fictional test", List.of(line(invoice, 0, 10000, 440))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(trace()).isEqualTo(withAnother);
        UUID reverseRequest = UUID.randomUUID();
        var reverse = commands.reverse(user, payment, original.id(), reverseRequest, 1, "Fictional reverse", List.of(line(invoice, 1, 0, 0)));
        assertThat(commands.reverse(user, payment, original.id(), reverseRequest, 1, "Fictional reverse", List.of(line(invoice, 1, 0, 0)))).isEqualTo(reverse);
        assertThatThrownBy(() -> commands.reverse(user, payment, original.id(), UUID.randomUUID(), 2, "Fictional duplicate", List.of(line(invoice, 2, 0, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("ALLOCATION_ALREADY_REVERSED");
        assertThat(trace()).isNotEqualTo(before);
    }

    @Test
    void auditFailureRollsBackConfirmationAndReversalWithAllVersionsAndHistory() {
        var original = confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 10000, 440)));
        jdbc.sql("CREATE FUNCTION fail_allocation_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'ALLOCATION_%' THEN RAISE EXCEPTION 'Fictional audit failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER fail_allocation_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION fail_allocation_audit()").update();
        try {
            String before = trace();
            assertThatThrownBy(() -> confirm(UUID.randomUUID(), 1, List.of(line(invoice, 1, 1000, 0)))).isInstanceOf(DataAccessException.class);
            assertThat(trace()).isEqualTo(before);
            assertThatThrownBy(() -> commands.reverse(user, payment, original.id(), UUID.randomUUID(), 1, "Fictional reverse", List.of(line(invoice, 1, 0, 0))))
                    .isInstanceOf(DataAccessException.class);
            assertThat(trace()).isEqualTo(before);
        } finally {
            jdbc.sql("DROP TRIGGER fail_allocation_audit ON audit_event").update();
            jdbc.sql("DROP FUNCTION fail_allocation_audit()").update();
        }
    }

    @Test
    void concurrentSameRequestReplaysAndDifferentPaymentsCannotOverAllocateSameInvoice() throws Exception {
        UUID request = UUID.randomUUID();
        var result = race(() -> confirm(request, 0, List.of(line(invoice, 0, 60000, 0))),
                () -> confirm(request, 0, List.of(line(invoice, 0, 60000, 0))));
        assertThat(result.getFirst()).isEqualTo(result.getLast());
        UUID another = payment(user, 100000);
        var competing = race(() -> confirm(UUID.randomUUID(), 1, List.of(line(invoice, 1, 40000, 0))),
                () -> commands.confirm(user, another, UUID.randomUUID(), 0, "Fictional race", List.of(line(invoice, 1, 40000, 0))));
        assertThat(competing.stream().filter(value -> value instanceof AllocationCommands.Operation)).hasSize(1);
        assertThat(competing.stream().filter(value -> value instanceof AllocationCommands.Conflict)).hasSize(1);
        assertThat(ledger.invoice(tenant, invoice).balance().outstanding().signum()).isZero();
    }

    @Test
    void simultaneousFeeLineIsAllowedButCancelledInvoiceAndIncompleteOrStaleReversalAreRejected() {
        UUID second = invoice(user, 1000);
        var original = confirm(UUID.randomUUID(), 0, List.of(line(invoice, 0, 100, 0), line(second, 0, 0, 440)));
        String before = trace();
        assertThatThrownBy(() -> commands.reverse(user, payment, original.id(), UUID.randomUUID(), 1, "Fictional incomplete",
                List.of(line(invoice, 1, 0, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.reverse(user, payment, original.id(), UUID.randomUUID(), 1, "Fictional stale",
                List.of(line(invoice, 0, 0, 0), line(second, 1, 0, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("VERSION_CONFLICT");
        assertThat(trace()).isEqualTo(before);
        UUID cancelled = invoice(user, 1000);
        mutations.cancel(user, cancelled, 0, "Fictional cancelled invoice");
        before = trace();
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), 1, List.of(line(cancelled, 1, 100, 0))))
                .isInstanceOf(AllocationCommands.Conflict.class).hasMessage("INVOICE_NOT_ACTIVE");
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void actualHttpRequiresSessionCsrfStrictNumbersAndHidesForeignResources() throws Exception {
        String body = json.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "expectedPaymentVersion", "0", "reason", "Fictional HTTP",
                "lines", List.of(Map.of("invoiceId", invoice, "expectedInvoiceVersion", "0", "cashAmount", 99560, "feeAmount", 440))));
        String path = "/api/payments/" + payment + "/allocations";
        mvc.perform(post(path).contentType("application/json").content(body).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json").content(body).with(auth(user))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post(path).contentType("application/json").content(body).with(auth(foreign)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(path).contentType("application/json").content(body.replace("99560", "99560.5")).with(auth(user)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType("application/json").content(body).with(auth(user)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.paymentVersion").value("1"));
        mvc.perform(post(path).contentType("application/json").content(body).with(auth(user)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.paymentVersion").value("1"));
    }

    AllocationCommands.Operation confirm(UUID request, long paymentVersion, List<AllocationCommands.Line> lines) {
        return commands.confirm(user, payment, request, paymentVersion, "Fictional test", lines);
    }
    AllocationCommands.Line line(UUID target, long version, long cash, long fee) { return new AllocationCommands.Line(target, version, cash, fee); }
    List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                start.await(); try { return action.call(); } catch (AllocationCommands.Conflict conflict) { return conflict; }
            })).toList();
            start.countDown();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        }
    }
}
