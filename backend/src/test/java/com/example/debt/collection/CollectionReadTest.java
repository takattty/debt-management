package com.example.debt.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import com.example.debt.reconciliation.LedgerRepository;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class CollectionReadTest {
    @Autowired JdbcClient jdbc;
    @Autowired CollectionQueries queries;
    @Autowired LedgerRepository ledger;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private final UUID customer = UUID.randomUUID();
    private final UUID foreignCustomer = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID invoiceBatch = UUID.randomUUID();
    private final UUID foreignBatch = UUID.randomUUID();
    private final UUID paymentBatch = UUID.randomUUID();
    private final List<UUID> invoiceIds = new ArrayList<>();
    private UUID overdue;
    private UUID otherDue;
    private UUID foreign;
    private TransactionTemplate transaction;
    private MockHttpSession alpha;
    static final LocalDate AS_OF = LocalDate.of(2026, 12, 1);

    @BeforeEach
    void ownFictionalInvoiceAndLedgerFixtures() throws Exception {
        transaction = new TransactionTemplate(transactions);
        customer(customer, AuthFixtures.ALPHA); customer(foreignCustomer, AuthFixtures.BETA);
        batch(invoiceBatch, AuthFixtures.ALPHA, AuthFixtures.ALPHA_ACCOUNTANT, "INVOICE");
        batch(foreignBatch, AuthFixtures.BETA, AuthFixtures.BETA_ADMIN, "INVOICE");
        batch(paymentBatch, AuthFixtures.ALPHA, AuthFixtures.ALPHA_ACCOUNTANT, "PAYMENT");
        overdue = invoice(LocalDate.of(2026, 11, 30), 100000, false);
        otherDue = invoice(LocalDate.of(2026, 11, 30), 40000, false);
        invoice(LocalDate.of(2026, 12, 2), 10000, false);
        UUID cancelled = invoice(LocalDate.of(2026, 11, 29), 30000, false);
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?").params(AuthFixtures.ALPHA, cancelled).update();
        foreign = invoice(LocalDate.of(2026, 11, 30), 50000, true);
        jdbc.sql("INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) VALUES (?,?,?,'2026-11-30','Fictional collection receipt',120000,?,?,CURRENT_TIMESTAMP)")
                .params(payment, AuthFixtures.ALPHA, payment.toString(), paymentBatch, AuthFixtures.ALPHA_ACCOUNTANT).update();
        alpha = AuthFixtures.login(mvc, "alpha-accountant@example.test");
    }

    @AfterEach
    void resetOnlyDedicatedJUnitDatabaseImmutableFixtures() {
        jdbc.sql("TRUNCATE collection_event,collection_state,allocation_line,allocation_operation,reconciliation_payment_state,payment").update();
        jdbc.sql("DELETE FROM invoice WHERE id IN (:ids)").param("ids", invoiceIds).update();
        jdbc.sql("DELETE FROM customer WHERE id IN (?,?)").params(customer, foreignCustomer).update();
        jdbc.sql("DELETE FROM import_batch WHERE id IN (?,?,?)").params(invoiceBatch, foreignBatch, paymentBatch).update();
    }

    @Test
    void tenThousandOverdueClaimsUse101KeysetPagesWithoutRepeatsOrForeignCancelledAndFutureRows() {
        long started = System.nanoTime();
        var inserted = jdbc.sql("""
                INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id)
                SELECT gen_random_uuid(),?,?,gen_random_uuid()::text,DATE '2026-11-29',DATE '2026-11-29',100,?
                FROM generate_series(1,10000) RETURNING id
                """).params(AuthFixtures.ALPHA, customer, invoiceBatch).query(UUID.class).list();
        invoiceIds.addAll(inserted);
        var expected = new java.util.HashSet<>(inserted);
        expected.add(overdue); expected.add(otherDue);
        var seen = new java.util.HashSet<UUID>();
        String cursor = null;
        int pages = 0;
        do {
            var page = queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.ALL, cursor, 100);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(100);
            for (var row : page.items()) {
                assertThat(row.eligible()).isTrue(); assertThat(row.effectiveOutstanding().signum()).isPositive();
                assertThat(seen.add(row.invoiceId())).isTrue();
            }
            cursor = page.nextCursor(); pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(101); assertThat(seen).isEqualTo(expected);
        System.out.println("COLLECTION_10K pages=" + pages + " invoices=" + seen.size() + " elapsedMs="
                + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Test
    void dueDayIsZeroAndTheNextBusinessDayHasOneDayOverdueWithoutInventingBuckets() throws Exception {
        var dueDay = queries.get(AuthFixtures.ALPHA, overdue, LocalDate.of(2026, 11, 30));
        assertThat(dueDay.daysOverdue()).isZero(); assertThat(dueDay.eligible()).isFalse();
        assertThat(queries.list(AuthFixtures.ALPHA, LocalDate.of(2026, 11, 30), CollectionQueries.View.ALL, null, 100).items()).isEmpty();
        mvc.perform(get("/api/collections/" + overdue + "?asOf=2026-12-01").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.daysOverdue").value(1)).andExpect(jsonPath("$.outstandingAmount").value(100000))
                .andExpect(jsonPath("$.eligible").value(true)).andExpect(jsonPath("$.version").value(0));
        var first = queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.ACTIONABLE, null, 1);
        var second = queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.ACTIONABLE, first.nextCursor(), 1);
        assertThat(List.of(first.items().getFirst().invoiceId(), second.items().getFirst().invoiceId())).containsExactlyInAnyOrder(overdue, otherDue);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void partialCashExplicitFeesFullSettlementAndReversalUseRealConservationWhileHistoryRemains() throws Exception {
        event("NOTE", "請求書再送の依頼", null, false, null, LocalDate.of(2026, 12, 5));
        UUID partial = allocate(39560, 440);
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).balance().outstanding()).isEqualTo(BigInteger.valueOf(60000));
        UUID completed = allocate(60000, 0);
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).eligible()).isFalse();
        mvc.perform(get("/api/collections/" + overdue + "?asOf=2026-12-01").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100000)).andExpect(jsonPath("$.outstandingAmount").value(0));
        assertThat(queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.ALL, null, 100).items())
                .extracting(CollectionQueries.Case::invoiceId).doesNotContain(overdue);
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).hasSize(1);
        reverse(completed);
        var restored = queries.get(AuthFixtures.ALPHA, overdue, AS_OF);
        assertThat(restored.eligible()).isTrue(); assertThat(restored.balance().outstanding()).isEqualTo(BigInteger.valueOf(60000));
        assertThat(restored.nextActionDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        reverse(partial);
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).balance().outstanding()).isEqualTo(BigInteger.valueOf(100000));
        assertThat(ledger.payment(AuthFixtures.ALPHA, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(120000));
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?").params(AuthFixtures.ALPHA, overdue).update();
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).eligible()).isFalse();
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).hasSize(1);
    }

    @Test
    void holdsKeepBalancesPlansAndHumanIdentifiableHistorySeparateFromActionableCandidates() throws Exception {
        event("NOTE", "請求書再送の依頼", null, false, null, LocalDate.of(2026, 12, 5));
        event("HOLD", "請求書再送の依頼", "入金確認待ち", true, "入金確認待ち", LocalDate.of(2026, 12, 5));
        assertThat(queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.ACTIONABLE, null, 100).items())
                .extracting(CollectionQueries.Case::invoiceId).doesNotContain(overdue);
        var held = queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.HELD, null, 100).items().getFirst();
        assertThat(held.invoiceId()).isEqualTo(overdue); assertThat(held.holdReason()).isEqualTo("入金確認待ち");
        assertThat(held.balance().outstanding()).isEqualTo(BigInteger.valueOf(100000));
        mvc.perform(get("/api/collections/" + overdue + "/history?limit=1").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorUserId").value(AuthFixtures.ALPHA_ACCOUNTANT.toString()))
                .andExpect(jsonPath("$.items[0].actorLabel").value("alpha-accountant@example.test"))
                .andExpect(jsonPath("$.items[0].nextActionDate").value("2026-12-05")).andExpect(jsonPath("$.nextCursor").value(1));
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 1, 1).items().getFirst().kind()).isEqualTo("HOLD");
        event("RELEASE", "請求書再送の依頼", "入金状況を確認済み", false, null, LocalDate.of(2026, 12, 5));
        assertThat(queries.list(AuthFixtures.ALPHA, AS_OF, CollectionQueries.View.HELD, null, 100).items()).isEmpty();
        for (String sql : List.of("DELETE FROM collection_event WHERE tenant_id=?", "UPDATE collection_event SET actor_label='forged' WHERE tenant_id=?",
                "DELETE FROM collection_state WHERE tenant_id=?", "UPDATE collection_state SET version=version+1 WHERE tenant_id=?")) {
            assertThatThrownBy(() -> jdbc.sql(sql).param(AuthFixtures.ALPHA).update()).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void typedBoundsStrictIsoTenantAndMissingDateAreRejectedWithoutCreatingCollectionState() throws Exception {
        mvc.perform(get("/api/collections?asOf=2026-12-01")).andExpect(status().isUnauthorized());
        for (String query : List.of("", "?asOf=2026-02-30", "?asOf=2026-12-01T00:00:00", "?asOf=0000-01-01", "?asOf=10000-01-01",
                "?asOf=2026-12-01&limit=0", "?asOf=2026-12-01&limit=101", "?asOf=2026-12-01&view=UNKNOWN", "?asOf=2026-12-01&cursor=bad")) {
            mvc.perform(get("/api/collections" + query).session(alpha)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/collections/" + foreign + "?asOf=2026-12-01").session(alpha)).andExpect(status().isNotFound());
        mvc.perform(get("/api/collections/" + foreign + "/history").session(alpha)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM collection_state").query(Long.class).single()).isZero();
        var ancient = invoice(LocalDate.of(1, 1, 1), 9007199254740991L, false);
        var extreme = queries.get(AuthFixtures.ALPHA, ancient, LocalDate.of(9999, 12, 31));
        assertThat(extreme.balance().outstanding()).isEqualTo(BigInteger.valueOf(9007199254740991L));
        assertThat(extreme.daysOverdue()).isEqualTo(3652058);
    }

    @Test
    void cancelledDetailExcludesOutstandingWithoutChangingOriginalFactsRawConservationOrHistoricalNotes() throws Exception {
        event("NOTE", "ACTIVE時の確認額100000円", null, false, null, LocalDate.of(2026, 12, 5));
        var historical = queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items();
        jdbc.sql("UPDATE invoice SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?")
                .params(AuthFixtures.ALPHA, overdue).update();
        var current = queries.get(AuthFixtures.ALPHA, overdue, AS_OF);
        assertThat(current.balance().amount()).isEqualTo(BigInteger.valueOf(100000));
        assertThat(current.balance().outstanding()).isEqualTo(BigInteger.valueOf(100000));
        assertThat(current.effectiveOutstanding()).isZero(); assertThat(current.eligible()).isFalse();
        assertThat(ledger.invoice(AuthFixtures.ALPHA, overdue).balance().outstanding()).isEqualTo(BigInteger.valueOf(100000));
        mvc.perform(get("/api/collections/" + overdue + "?asOf=2026-12-01").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceStatus").value("CANCELLED")).andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.amount").value(100000)).andExpect(jsonPath("$.outstandingAmount").value(0));
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).isEqualTo(historical);
    }

    @Test
    void holdEventsRequireTheirOwnExplicitReasonAtTheDatabaseBoundary() {
        assertThatThrownBy(() -> event("HOLD", null, null, true, "入金確認待ち", null)).isInstanceOf(DataAccessException.class);
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).version()).isZero();
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).isEmpty();
    }

    private UUID allocate(long cash, long fee) {
        return transaction.execute(status -> {
            var locked = ledger.lockPositions(AuthFixtures.ALPHA, payment, List.of(overdue));
            UUID id = operation(locked.payment().version() + 1, "CONFIRM", null);
            jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,0,?,?)").params(AuthFixtures.ALPHA, id, overdue, cash, fee).update();
            return id;
        });
    }
    private void reverse(UUID original) {
        transaction.executeWithoutResult(status -> {
            var locked = ledger.lockPositions(AuthFixtures.ALPHA, payment, List.of(overdue));
            operation(locked.payment().version() + 1, "REVERSE", original);
        });
    }
    private UUID operation(long version, String kind, UUID original) {
        UUID id = UUID.randomUUID();
        jdbc.sql("UPDATE reconciliation_payment_state SET version=? WHERE tenant_id=? AND payment_id=?").params(version, AuthFixtures.ALPHA, payment).update();
        jdbc.sql("INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason) VALUES (?,?,?,?,?,?,?,?,'Collection balance fixture')")
                .params(id, AuthFixtures.ALPHA, payment, UUID.randomUUID(), kind, original, version, AuthFixtures.ALPHA_ACCOUNTANT).update();
        return id;
    }
    private void event(String kind, String note, String reason, boolean held, String holdReason, LocalDate nextDate) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO collection_state(tenant_id,invoice_id) VALUES (?,?) ON CONFLICT DO NOTHING").params(AuthFixtures.ALPHA, overdue).update();
            long version = jdbc.sql("SELECT version FROM collection_state WHERE tenant_id=? AND invoice_id=? FOR UPDATE")
                    .params(AuthFixtures.ALPHA, overdue).query(Long.class).single() + 1;
            jdbc.sql("UPDATE collection_state SET version=?,held=?,hold_reason=?,latest_note=?,next_action_date=? WHERE tenant_id=? AND invoice_id=?")
                    .params(version, held, holdReason, note, nextDate, AuthFixtures.ALPHA, overdue).update();
            jdbc.sql("INSERT INTO collection_event(tenant_id,invoice_id,version,kind,note,reason,actor_user_id,held,hold_reason,next_action_date) VALUES (?,?,?,?,?,?,?,?,?,?)")
                    .params(AuthFixtures.ALPHA, overdue, version, kind, note, reason, AuthFixtures.ALPHA_ACCOUNTANT, held, holdReason, nextDate).update();
        });
    }
    private void customer(UUID id, UUID tenant) {
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'架空の回収先')").params(id, tenant, id.toString()).update();
    }
    private void batch(UUID id, UUID tenant, UUID actor, String kind) {
        jdbc.sql("INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,version,created_by_user_id,created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count) VALUES (?,?,?,1,decode(?,'hex'),'CONFIRMED',1,?,'2026-10-29T00:00:00Z','2026-10-30T00:00:00Z',?,'2026-10-29T00:00:00Z',1,0)")
                .params(id, tenant, kind, id.toString().replace("-", "").repeat(2), actor, actor).update();
    }
    private UUID invoice(LocalDate due, long amount, boolean otherTenant) {
        UUID id = UUID.randomUUID(); invoiceIds.add(id);
        jdbc.sql("INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id) VALUES (?,?,?,?,?,?,?,?)")
                .params(id, otherTenant ? AuthFixtures.BETA : AuthFixtures.ALPHA, otherTenant ? foreignCustomer : customer, id.toString(), due, due, amount,
                        otherTenant ? foreignBatch : invoiceBatch).update();
        return id;
    }
}
