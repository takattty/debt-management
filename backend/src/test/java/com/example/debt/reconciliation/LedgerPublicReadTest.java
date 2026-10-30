package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LedgerPublicReadTest extends LedgerPublicFixtures {
    @Test
    void partialExplicitFeesFullOverpaymentAndWholeReversalsUseActualLedgerWithoutChangingOriginals() throws Exception {
        assertPublic(100000, 120000, 0);
        UUID partial = allocate(payment, invoice, 60000, 440);
        assertPublic(39560, 60000, 1);
        UUID remainder = allocate(payment, invoice, 39560, 0);
        assertPublic(0, 20440, 2);
        reverse(payment, invoice, partial);
        assertPublic(60440, 80440, 3);
        reverse(payment, invoice, remainder);
        assertPublic(100000, 120000, 4);
        UUID fee = allocate(payment, invoice, 0, 1);
        assertPublic(99999, 120000, 5);
        reverse(payment, invoice, fee);
        assertPublic(100000, 120000, 6);
        assertThat(jdbc.sql("SELECT count(*) FROM allocation_operation WHERE tenant_id=?").param(tenant).query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM allocation_line WHERE tenant_id=?").param(tenant).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void cancelledOriginalsHaveZeroOutstandingAndReadsCannotJoinOtherTenants() throws Exception {
        UUID foreignInvoice = invoice(foreign, 777);
        payment(foreign, 999);
        mutations.cancel(user, invoice, 0, "Fictional cancellation");
        String before = trace();
        mvc.perform(get("/api/invoices/" + invoice).with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100000)).andExpect(jsonPath("$.outstandingAmount").value(0))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/receivables?status=ALL").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].outstandingAmount").value(0));
        mvc.perform(get("/api/receivables").with(auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/invoices/" + foreignInvoice).with(auth(user))).andExpect(status().isNotFound());
        mvc.perform(get("/api/invoices/" + invoice).with(auth(foreign))).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments").with(auth(user))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(120000));
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void maximumJavascriptIntegerAmountsRemainExactWithFeesAndKeysetPages() throws Exception {
        long maximum = 9_007_199_254_740_991L;
        UUID target = invoice(user, maximum);
        UUID receipt = payment(user, maximum);
        allocate(receipt, target, maximum - 1, 1);
        var view = invoices.view(tenant, target);
        assertThat(view.invoice().amount()).isEqualTo(maximum);
        assertThat(view.outstanding()).isZero();
        mvc.perform(get("/api/invoices/" + target).with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(maximum)).andExpect(jsonPath("$.outstandingAmount").value(0));
        var first = payments.list(tenant, null, null, null, 1);
        var second = payments.list(tenant, null, null, first.nextCursor(), 1);
        assertThat(first.items()).hasSize(1); assertThat(second.items()).hasSize(1); assertThat(second.nextCursor()).isNull();
        assertThat(java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).filter(row -> row.id().equals(receipt)).findFirst().orElseThrow().unallocated())
                .isEqualTo(1);
        var page = receivables.list(tenant, customer, null, null, null, 1);
        assertThat(receivables.list(tenant, customer, null, null, page.nextCursor(), 1).items()).hasSize(1);
    }

    @Test
    void concurrentAllocationAndReversalExposeBalanceAndInvoiceVersionFromOneSnapshot() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 40; index++) {
                    UUID original = allocate(payment, invoice, 1, 1);
                    reverse(payment, invoice, original);
                }
                return null;
            });
            start.countDown();
            int reads = 0;
            do {
                var view = invoices.view(tenant, invoice);
                assertSnapshot(view.invoice().version(), view.outstanding());
                var row = receivables.list(tenant, customer, null, null, null, 1).items().getFirst();
                assertSnapshot(row.version(), row.outstanding());
                long balance = payments.list(tenant, null, null, null, 1).items().getFirst().unallocated();
                assertThat(balance).isBetween(119999L, 120000L);
                reads++;
            } while (!writer.isDone() || reads < 40);
            writer.get(20, TimeUnit.SECONDS);
        }
        assertPublic(100000, 120000, 80);
    }

    private void assertSnapshot(long version, long outstanding) {
        assertThat(outstanding).isEqualTo(version % 2 == 0 ? 100000 : 99998);
    }
    private void assertPublic(long outstanding, long unallocated, long expectedVersion) throws Exception {
        String before = trace();
        assertThat(invoices.view(tenant, invoice).outstanding()).isEqualTo(outstanding);
        assertThat(ledger.invoice(tenant, invoice).balance().outstanding()).isEqualTo(BigInteger.valueOf(outstanding));
        assertThat(ledger.payment(tenant, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(unallocated));
        mvc.perform(get("/api/invoices/" + invoice).with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100000)).andExpect(jsonPath("$.outstandingAmount").value(outstanding))
                .andExpect(jsonPath("$.version").value(expectedVersion));
        mvc.perform(get("/api/receivables?customerId=" + customer).with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value(100000)).andExpect(jsonPath("$.items[0].outstandingAmount").value(outstanding))
                .andExpect(jsonPath("$.items[0].version").value(expectedVersion));
        mvc.perform(get("/api/payments").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value(120000)).andExpect(jsonPath("$.items[0].unallocatedAmount").value(unallocated));
        assertThat(trace()).isEqualTo(before);
    }
}
