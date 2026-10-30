package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.TenantUser;
import com.example.debt.access.UserRole;
import com.example.debt.payment.PayerAliases;
import com.example.debt.payment.PaymentValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class PaymentCandidatesTest {
    @Autowired PaymentCandidates candidates;
    @Autowired LedgerRepository ledger;
    @Autowired PayerAliases aliases;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private TenantUser user;
    private TenantUser foreignUser;
    private UUID invoiceBatch;
    private UUID paymentBatch;
    private UUID foreignInvoiceBatch;
    private UUID foreignPaymentBatch;
    private UUID customer;
    private UUID secondCustomer;
    private UUID foreignCustomer;
    private UUID payment;

    @BeforeEach
    void fictionalFacts() {
        user = user(tenant); foreignUser = user(other);
        invoiceBatch = batch(user, "INVOICE"); paymentBatch = batch(user, "PAYMENT");
        foreignInvoiceBatch = batch(foreignUser, "INVOICE"); foreignPaymentBatch = batch(foreignUser, "PAYMENT");
        customer = customer(tenant); secondCustomer = customer(tenant); foreignCustomer = customer(other);
        payment = payment(user, "架空名義", 100);
    }

    @AfterEach
    void clearOnlyDedicatedTestDatabaseAndOwnedFacts() {
        jdbc.sql("TRUNCATE allocation_line,allocation_operation,reconciliation_payment_state,payment,payer_alias").update();
        for (UUID scope : List.of(tenant, other)) {
            jdbc.sql("DELETE FROM audit_event WHERE tenant_id=?").param(scope).update();
            for (String table : List.of("invoice", "customer", "import_batch", "app_user", "tenant")) {
                jdbc.sql("DELETE FROM " + table + " WHERE " + (table.equals("tenant") ? "id" : "tenant_id") + "=?").param(scope).update();
            }
        }
    }

    @Test
    void manualCorrespondenceOutranksAmountThenDueAndUuidWithBoundedKeysets() {
        UUID early = invoice(tenant, secondCustomer, 100, "0001-01-01", UUID.randomUUID());
        UUID laterManual = invoice(tenant, customer, 80, "9999-12-31", UUID.randomUUID());
        UUID firstTie = invoice(tenant, customer, 100, "1500-03-01", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        UUID lastTie = invoice(tenant, customer, 100, "1500-03-01", UUID.fromString("80000000-0000-0000-0000-000000000001"));
        aliases.register(user, "　架空名義　", customer);
        var firstPage = candidates.list(tenant, payment, null, 1);
        assertThat(firstPage.matchedCustomers()).isEqualTo(1);
        List<UUID> found = new ArrayList<>(); String cursor = null;
        do {
            var page = candidates.list(tenant, payment, cursor, 1);
            page.items().forEach(row -> found.add(row.invoiceId())); cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(found).containsExactly(firstTie, lastTie, laterManual, early);
        var all = candidates.list(tenant, payment, null, 100).items();
        assertThat(all.get(2).manualPayerMatch()).isTrue(); assertThat(all.get(2).exactAmountMatch()).isFalse();
        assertThat(all.get(3).manualPayerMatch()).isFalse(); assertThat(all.get(3).exactAmountMatch()).isTrue();
    }

    @Test
    void namesStayDistinctAndMissingOrMultipleMappingsNeverHideOtherCustomers() throws Exception {
        UUID first = invoice(tenant, customer, 100, "2026-10-30", UUID.randomUUID());
        UUID second = invoice(tenant, secondCustomer, 100, "2026-10-29", UUID.randomUUID());
        assertThat(candidates.list(tenant, payment, null, 100).matchedCustomers()).isZero();
        var names = List.of("ｶｸｳ", "カクウ", "KAKUU", "kakuu", "é", "e\u0301");
        var mappings = new ArrayList<PayerAliases.Row>();
        var originals = new ArrayList<UUID>();
        for (String name : names) {
            UUID party = customer(tenant);
            mappings.add(aliases.register(user, name, party));
            originals.add(invoice(tenant, party, 100, "2026-10-31", UUID.randomUUID()));
        }
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            UUID receipt = payment(user, "　" + name + "　", 100);
            var page = candidates.list(tenant, receipt, null, 100);
            assertThat(page.payment().payerName()).isEqualTo(name); assertThat(page.matchedCustomers()).isEqualTo(1);
            assertThat(page.items().getFirst().invoiceId()).isEqualTo(originals.get(index));
            assertThat(page.items()).extracting(PaymentCandidates.Candidate::invoiceId).contains(first, second);
            assertThat(page.items().stream().filter(PaymentCandidates.Candidate::manualPayerMatch)).hasSize(1);
            aliases.deactivate(user, mappings.get(index).id(), 0, "架空の検査終了");
            assertThat(candidates.list(tenant, receipt, null, 100).matchedCustomers()).isZero();
        }
        aliases.register(user, "架空名義", customer); aliases.register(user, "架空名義", secondCustomer);
        mvc.perform(get("/api/payments/" + payment + "/candidates").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.payerMatchStatus").value("MULTIPLE_CUSTOMERS"))
                .andExpect(jsonPath("$.matchedCustomerCount").value(2)).andExpect(jsonPath("$.requiresHumanConfirmation").value(true));
    }

    @Test
    void currentCashFeeAndWholeReversalDriveCandidatesWithoutDecidingFeeOnlyPolicy() throws Exception {
        UUID untouched = invoice(tenant, customer, 100, "2026-10-30", UUID.randomUUID());
        UUID partial = invoice(tenant, customer, 100, "2026-10-29", UUID.randomUUID());
        UUID paid = invoice(tenant, customer, 100, "2026-10-28", UUID.randomUUID());
        UUID cancelled = invoice(tenant, customer, 100, "2026-10-27", UUID.randomUUID());
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=?").param(cancelled).update();
        UUID otherReceipt = payment(user, "別入金の明示手数料", 100);
        UUID fee = allocate(otherReceipt, paid, 0, 100);
        UUID original = allocate(payment, partial, 25, 5);
        var page = candidates.list(tenant, payment, null, 100);
        assertThat(page.payment().unallocated()).isEqualTo(75);
        assertThat(page.items()).extracting(PaymentCandidates.Candidate::invoiceId).containsExactly(partial, untouched);
        assertThat(page.items().getFirst().outstanding()).isEqualTo(70);
        UUID exact = invoice(tenant, customer, 75, "9999-12-31", UUID.randomUUID());
        assertThat(candidates.list(tenant, payment, null, 100).items().getFirst().invoiceId()).isEqualTo(exact);
        allocate(payment, untouched, 75, 0);
        String before = trace();
        mvc.perform(get("/api/payments/" + payment + "/candidates").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.cashCandidateStatus").value("NO_UNALLOCATED_CASH"))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.requiresHumanConfirmation").value(true));
        assertThat(trace()).isEqualTo(before);
        reverse(payment, original); reverse(otherReceipt, fee);
        assertThat(candidates.list(tenant, payment, null, 100).payment().unallocated()).isEqualTo(25);
        assertThat(candidates.list(tenant, payment, null, 100).items()).extracting(PaymentCandidates.Candidate::invoiceId).contains(paid, partial);
    }

    @Test
    void getDoesNotInitializeStateOrAuditAndPreservesSafeMoneyDatesAndBigintVersions() throws Exception {
        UUID receipt = payment(user, "最大額", PaymentValues.MAX_AMOUNT_JPY);
        UUID id = invoice(tenant, customer, PaymentValues.MAX_AMOUNT_JPY, "1582-10-05", UUID.randomUUID());
        jdbc.sql("UPDATE invoice SET version=? WHERE id=?").params(Long.MAX_VALUE, id).update();
        String before = trace();
        mvc.perform(get("/api/payments/" + receipt + "/candidates").with(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedPaymentVersion").value("0"))
                .andExpect(jsonPath("$.unallocatedAmount").value(PaymentValues.MAX_AMOUNT_JPY))
                .andExpect(jsonPath("$.transferDate").value("0001-01-01"))
                .andExpect(jsonPath("$.items[0].expectedInvoiceVersion").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.items[0].dueDate").value("1582-10-05"))
                .andExpect(jsonPath("$.items[0].exactAmountMatch").value(true));
        assertThat(trace()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_payment_state WHERE tenant_id=?").param(tenant).query(Long.class).single()).isZero();
    }

    @Test
    void isolatesTenantsAndRejectsMalformedOrCrossPaymentCursorsAndLimits() throws Exception {
        UUID hidden = invoice(other, foreignCustomer, 100, "0001-01-01", UUID.randomUUID());
        UUID first = invoice(tenant, customer, 100, "2026-10-30", UUID.randomUUID());
        invoice(tenant, secondCustomer, 100, "2026-10-31", UUID.randomUUID());
        aliases.register(foreignUser, "架空名義", foreignCustomer);
        var page = candidates.list(tenant, payment, null, 1);
        assertThat(page.matchedCustomers()).isZero(); assertThat(page.items().getFirst().invoiceId()).isEqualTo(first).isNotEqualTo(hidden);
        mvc.perform(get("/api/payments/" + payment + "/candidates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/" + payment + "/candidates").with(auth(foreignUser)).header("X-Tenant-Id", tenant)).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/" + UUID.randomUUID() + "/candidates").with(auth(user))).andExpect(status().isNotFound());
        for (String query : List.of("limit=0", "limit=101", "cursor=bad", "cursor=" + page.nextCursor().replace(":1:0:", ":2:0:"))) {
            mvc.perform(get("/api/payments/" + payment + "/candidates?" + query).with(auth(user))).andExpect(status().isBadRequest());
        }
        UUID another = payment(user, "架空名義", 100);
        assertThatThrownBy(() -> candidates.list(tenant, another, page.nextCursor(), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomizedCashFeeAndReversalReadsMatchTheIndependentLedgerRepository() {
        var receipts = List.of(payment(user, "property", 100000), payment(user, "property-other", 100000));
        var invoices = new ArrayList<UUID>();
        for (int index = 0; index < 20; index++) { invoices.add(invoice(tenant, index % 2 == 0 ? customer : secondCustomer, 2000, "2026-11-30", UUID.randomUUID())); }
        var random = new Random(260430);
        record Original(UUID receipt, UUID operation) { }
        var originals = new ArrayList<Original>();
        for (int iteration = 0; iteration < 40; iteration++) {
            if (!originals.isEmpty() && random.nextInt(4) == 0) {
                var original = originals.remove(random.nextInt(originals.size())); reverse(original.receipt(), original.operation());
            } else {
                UUID receipt = receipts.get(random.nextInt(receipts.size()));
                originals.add(new Original(receipt, allocate(receipt, invoices.get(random.nextInt(invoices.size())), random.nextInt(30), random.nextInt(10) + 1)));
            }
            for (UUID receipt : receipts) {
                var page = candidates.list(tenant, receipt, null, 100);
                var independent = ledger.payment(tenant, receipt);
                assertThat(page.payment().unallocated()).isEqualTo(independent.balance().unallocated().longValueExact());
                assertThat(page.payment().version()).isEqualTo(independent.version());
                assertThat(page.items()).hasSize(20);
                page.items().forEach(row -> {
                    var position = ledger.invoice(tenant, row.invoiceId());
                    assertThat(row.outstanding()).isEqualTo(position.balance().outstanding().longValueExact());
                    assertThat(row.version()).isEqualTo(position.version());
                    assertThat(row.exactAmountMatch()).isEqualTo(row.outstanding() == page.payment().unallocated());
                });
            }
        }
    }

    @Test
    void concurrentCommittedChangesNeverMixPaymentAndInvoiceSnapshots() throws Exception {
        UUID id = invoice(tenant, customer, 100, "2026-11-30", UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> {
                start.await();
                for (int index = 0; index < 10; index++) { UUID original = allocate(payment, id, 25, 5); reverse(payment, original); }
                return true;
            });
            var reader = pool.submit(() -> {
                start.await();
                for (int index = 0; index < 40; index++) {
                    var page = candidates.list(tenant, payment, null, 100);
                    boolean original = page.payment().version() % 2 == 0;
                    assertThat(page.payment().unallocated()).isEqualTo(original ? 100 : 75);
                    assertThat(page.items().getFirst().outstanding()).isEqualTo(original ? 100 : 70);
                    assertThat(page.items().getFirst().version()).isEqualTo(page.payment().version());
                }
                return true;
            });
            start.countDown(); assertThat(writer.get(30, TimeUnit.SECONDS)).isTrue(); assertThat(reader.get(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tenThousandInvoicesPageWithoutDuplicatesAndNeverHideUnmatchedCustomers() {
        jdbc.sql("""
                INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id)
                SELECT gen_random_uuid(),?,?, 'CAND-' || n,'2026-10-30','2026-11-30',100,? FROM generate_series(1,10000) n
                """).params(tenant, customer, invoiceBatch).update();
        long started = System.nanoTime(); var seen = new java.util.HashSet<UUID>(); String cursor = null;
        do {
            var page = candidates.list(tenant, payment, cursor, 100);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(100);
            page.items().forEach(row -> { assertThat(seen.add(row.invoiceId())).isTrue(); assertThat(row.manualPayerMatch()).isFalse(); });
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(10000);
        System.out.println("CANDIDATE_10K browseMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private UUID allocate(UUID receipt, UUID invoice, long cash, long fee) {
        return new TransactionTemplate(transactions).execute(status -> {
            var locked = ledger.lockPositions(tenant, receipt, List.of(invoice));
            UUID operation = header(receipt, locked.payment().version() + 1, "CONFIRM", null);
            jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,?,?,?)").params(tenant, operation, invoice, locked.invoices().getFirst().version(), cash, fee).update();
            jdbc.sql("UPDATE invoice SET version=version+1 WHERE tenant_id=? AND id=?").params(tenant, invoice).update();
            return operation;
        });
    }
    private void reverse(UUID receipt, UUID original) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var ids = jdbc.sql("SELECT invoice_id FROM allocation_line WHERE tenant_id=? AND operation_id=?").params(tenant, original).query(UUID.class).list();
            var locked = ledger.lockPositions(tenant, receipt, ids);
            header(receipt, locked.payment().version() + 1, "REVERSE", original);
            ids.forEach(id -> jdbc.sql("UPDATE invoice SET version=version+1 WHERE tenant_id=? AND id=?").params(tenant, id).update());
        });
    }
    private UUID header(UUID receipt, long version, String kind, UUID original) {
        UUID id = UUID.randomUUID();
        jdbc.sql("UPDATE reconciliation_payment_state SET version=? WHERE tenant_id=? AND payment_id=?").params(version, tenant, receipt).update();
        jdbc.sql("INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(id, tenant, receipt, UUID.randomUUID(), kind, original, version, user.userId(), "Fictional internal candidate fixture").update();
        return id;
    }
    private TenantUser user(UUID scope) {
        UUID id = UUID.randomUUID(); String email = id + "@example.test";
        jdbc.sql("INSERT INTO tenant(id,name) VALUES (?,'Fictional candidate tenant')").param(scope).update();
        jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,'$argon2id$sql-fixture','ADMIN')").params(id, scope, email).update();
        return new TenantUser(id, scope, email, UserRole.ADMIN);
    }
    private UUID batch(TenantUser owner, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,version,created_by_user_id,
                    created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count)
                VALUES (?,?,?,1,decode(?,'hex'),'CONFIRMED',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day',?,CURRENT_TIMESTAMP,1,0)
                """).params(id, owner.tenantId(), kind, "b".repeat(64), owner.userId(), owner.userId()).update();
        return id;
    }
    private UUID customer(UUID scope) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'Fictional candidate customer')").params(id, scope, id.toString()).update();
        return id;
    }
    private UUID payment(TenantUser owner, String payer, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) VALUES (?,?,?,'0001-01-01',?,?,?,?,CURRENT_TIMESTAMP)")
                .params(id, owner.tenantId(), id.toString(), PaymentValues.text(payer, 200), amount,
                        owner.tenantId().equals(tenant) ? paymentBatch : foreignPaymentBatch, owner.userId()).update();
        return id;
    }
    private UUID invoice(UUID scope, UUID customerId, long amount, String date, UUID id) {
        jdbc.sql("INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id) VALUES (?,?,?,?,?,?,?,?)")
                .params(id, scope, customerId, id.toString(), PaymentValues.date(date), PaymentValues.date(date), amount, scope.equals(tenant) ? invoiceBatch : foreignInvoiceBatch).update();
        return id;
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(TenantUser owner) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.authorities()));
    }
    private String trace() {
        StringBuilder trace = new StringBuilder();
        for (String table : List.of("payment", "invoice", "payer_alias", "reconciliation_payment_state", "allocation_operation", "allocation_line", "audit_event")) {
            trace.append(jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM " + table + " t WHERE tenant_id=?")
                    .param(tenant).query(String.class).single());
        }
        return trace.toString();
    }
}
