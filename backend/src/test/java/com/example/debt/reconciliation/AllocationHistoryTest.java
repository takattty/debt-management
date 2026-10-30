package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AllocationHistoryTest extends LedgerPublicFixtures {
    @Autowired AllocationHistory history;
    @Autowired AllocationCommands commands;

    @Test
    void emptyHistoryDoesNotInitializeStateWriteAuditOrExposeForeignPayment() throws Exception {
        String before = trace();
        var empty = history.list(tenant, payment, "0", 100);
        assertThat(empty.paymentVersion()).isZero(); assertThat(empty.items()).isEmpty(); assertThat(empty.nextCursor()).isNull();
        mvc.perform(get(path()).with(auth(user))).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.expectedPaymentVersion").value("0")).andExpect(jsonPath("$.items").isEmpty());
        assertThat(trace()).isEqualTo(before);
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).with(auth(foreign))).andExpect(status().isNotFound());
        for (String cursor : List.of("01", "-1", "1e0", "9223372036854775808")) {
            mvc.perform(get(path()).queryParam("cursor", cursor).with(auth(user))).andExpect(status().isBadRequest());
        }
        assertThatThrownBy(() -> history.list(tenant, payment, "0", 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void immutableCrossCustomerLinesActorAndReasonHaveCurrentVersionsAndWholeReversalLinks() throws Exception {
        UUID second = invoice(user, 50000);
        UUID anotherCustomer = customer(tenant);
        jdbc.sql("UPDATE invoice SET customer_id=? WHERE tenant_id=? AND id=?").params(anotherCustomer, tenant, second).update();
        UUID request = UUID.randomUUID();
        var original = commands.confirm(user, payment, request, 0, "Fictional human-confirmed fee", List.of(
                new AllocationCommands.Line(invoice, 0, 99560, 440), new AllocationCommands.Line(second, 0, 20000, 0)));
        var reversal = commands.reverse(user, payment, original.id(), UUID.randomUUID(), 1, "Fictional whole reversal", List.of(
                new AllocationCommands.Line(invoice, 1, 0, 0), new AllocationCommands.Line(second, 1, 0, 0)));
        correct(2, 110000);
        String before = trace();
        var page = history.list(tenant, payment, "0", 100);
        assertThat(page.paymentVersion()).isEqualTo(2); assertThat(page.items()).hasSize(2);
        var confirmed = page.items().getFirst(); var reversed = page.items().getLast();
        assertThat(confirmed.id()).isEqualTo(original.id()); assertThat(confirmed.requestId()).isEqualTo(request);
        assertThat(confirmed.actorUserId()).isEqualTo(user.userId()); assertThat(confirmed.actorEmail()).isEqualTo(user.email());
        assertThat(confirmed.reason()).isEqualTo("Fictional human-confirmed fee"); assertThat(confirmed.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(confirmed.reversedByOperationId()).isEqualTo(reversal.id()); assertThat(reversed.reversesId()).isEqualTo(original.id());
        assertThat(reversed.lines()).isEqualTo(confirmed.lines());
        assertThat(confirmed.lines()).extracting(AllocationHistory.Line::customerId).containsExactlyInAnyOrder(customer, anotherCustomer);
        var firstLine = confirmed.lines().stream().filter(line -> line.invoiceId().equals(invoice)).findFirst().orElseThrow();
        assertThat(firstLine.cash()).isEqualTo(99560); assertThat(firstLine.fee()).isEqualTo(440);
        assertThat(firstLine.observedInvoiceVersion()).isZero(); assertThat(firstLine.currentInvoiceVersion()).isEqualTo(3);
        mvc.perform(get(path()).with(auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].paymentVersion").value("1"))
                .andExpect(jsonPath("$.items[0].actorEmail").value(user.email())).andExpect(jsonPath("$.items[1].reversesId").value(original.id().toString()));
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void ascendingKeysetPagesOperationsWithoutSplittingOrDuplicatingTheirLines() {
        UUID second = invoice(user, 100000);
        for (int index = 0; index < 7; index++) {
            commands.confirm(user, payment, UUID.randomUUID(), index, "Fictional history page", List.of(
                    new AllocationCommands.Line(invoice, index, 1, 0), new AllocationCommands.Line(second, index, 1, 0)));
        }
        String cursor = "0";
        var ids = new java.util.ArrayList<UUID>();
        do {
            var page = history.list(tenant, payment, cursor, 2);
            page.items().forEach(entry -> { ids.add(entry.id()); assertThat(entry.lines()).hasSize(2); });
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).hasSize(7).doesNotHaveDuplicates();
        assertThat(history.list(tenant, payment, "7", 2).items()).isEmpty();
    }

    @Test
    void exactBigintInvoiceVersionsAndSafeMaximumAmountsSurviveHttpWithoutNumberConversion() throws Exception {
        long maximum = 9007199254740991L;
        UUID target = invoice(user, maximum); UUID receipt = payment(user, maximum);
        jdbc.sql("UPDATE invoice SET version=? WHERE tenant_id=? AND id=?").params(maximum + 2, tenant, target).update();
        commands.confirm(user, receipt, UUID.randomUUID(), 0, "Fictional exact history", List.of(new AllocationCommands.Line(target, maximum + 2, maximum, 0)));
        mvc.perform(get("/api/payments/" + receipt + "/allocations").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lines[0].observedInvoiceVersion").value("9007199254740993"))
                .andExpect(jsonPath("$.items[0].lines[0].currentInvoiceVersion").value("9007199254740994"))
                .andExpect(jsonPath("$.items[0].lines[0].cashAmount").value(maximum));
    }

    @Test
    void concurrentConfirmationAndReversalNeverMixVersionsLinesAndCurrentReversalState() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                start.await();
                for (long version = 0; version < 30; version += 2) {
                    var original = commands.confirm(user, payment, UUID.randomUUID(), version, "Fictional history race",
                            List.of(new AllocationCommands.Line(invoice, version, 1, 0)));
                    commands.reverse(user, payment, original.id(), UUID.randomUUID(), version + 1, "Fictional history reverse",
                            List.of(new AllocationCommands.Line(invoice, version + 1, 0, 0)));
                }
                return true;
            });
            start.countDown();
            int reads = 0;
            do {
                var page = history.list(tenant, payment, "0", 100);
                assertThat(page.items()).hasSize((int) page.paymentVersion());
                for (var entry : page.items()) {
                    assertThat(entry.lines()).hasSize(1);
                    assertThat(entry.lines().getFirst().currentInvoiceVersion()).isEqualTo(page.paymentVersion());
                    if (entry.kind().equals("CONFIRM")) {
                        boolean reversed = entry.paymentVersion() < page.paymentVersion();
                        assertThat(entry.reversedByOperationId() != null).isEqualTo(reversed);
                    }
                }
                reads++;
            } while (!writer.isDone() || reads < 10);
            assertThat(writer.get(15, TimeUnit.SECONDS)).isTrue();
        }
    }
    private String path() { return "/api/payments/" + payment + "/allocations"; }
}
