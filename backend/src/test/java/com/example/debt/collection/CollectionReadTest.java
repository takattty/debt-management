package com.example.debt.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.reconciliation.LedgerRepository;
import java.math.BigInteger;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

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
    @Autowired CollectionActions actions;
    @Autowired CollectionDrafts drafts;
    @Autowired UserAccounts users;
    private final JsonMapper json = JsonMapper.builder().build();
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
    void notePlanHoldAndReasonedReleaseRemainSharedAndDoNotTouchInvoiceOrPaymentFacts() throws Exception {
        mvc.perform(post("/api/collections/" + overdue + "/actions").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(noteBody(0, "請求書再送の依頼", "2026-12-05"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.event.actorLabel").value("alpha-accountant@example.test"));
        mvc.perform(post("/api/collections/" + overdue + "/hold").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":1,\"reason\":\"入金確認待ち\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(true)).andExpect(jsonPath("$.nextActionDate").value("2026-12-05"));
        mvc.perform(post("/api/collections/" + overdue + "/actions").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content(noteBody(2, "担当者交代後の確認", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(true)).andExpect(jsonPath("$.nextActionDate").doesNotExist());
        mvc.perform(post("/api/collections/" + overdue + "/release").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":3,\"reason\":\"確認が完了したため解除\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(false)).andExpect(jsonPath("$.version").value(4));
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).extracting(CollectionQueries.Event::kind)
                .containsExactly("NOTE", "HOLD", "NOTE", "RELEASE");
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).invoiceVersion()).isZero();
        assertThat(ledger.payment(AuthFixtures.ALPHA, payment).balance().unallocated()).isEqualTo(BigInteger.valueOf(120000));
        assertThat(audits()).isEqualTo(4);
        for (String sql : List.of("DELETE FROM audit_event WHERE tenant_id=? AND entity_id=? AND action LIKE 'COLLECTION_%'",
                "UPDATE audit_event SET details='{}'::jsonb WHERE tenant_id=? AND entity_id=? AND action LIKE 'COLLECTION_%'")) {
            assertThatThrownBy(() -> jdbc.sql(sql).params(AuthFixtures.ALPHA, overdue).update()).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void nextActionMemberMustBeExplicitAndStrictDatesMalformedJsonAndUnknownFieldsNeverMutate() throws Exception {
        actions.record(user(), overdue, 0, "元の対応予定", LocalDate.of(2026, 12, 5));
        for (String body : List.of("{\"version\":1,\"note\":\"missing date\"}", "{\"version\":1,\"nextActionDate\":null}",
                "{\"note\":\"missing version\",\"nextActionDate\":null}", "{\"version\":null,\"note\":\"null version\",\"nextActionDate\":null}", "{", noteBody(1, " ", null),
                noteBody(1, "理由", "2026-12-05T00:00:00"), noteBody(1, "理由", "2026-02-30"), noteBody(1, "理由", "0000-01-01"),
                noteBody(1, "理由", null).replace("null", "[2026,12,5]"),
                noteBody(1, "理由", null).replace("}", ",\"tenantId\":\"ignored\"}"))) {
            mvc.perform(post("/api/collections/" + overdue + "/actions").session(alpha).with(csrf().asHeader()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        var before = queries.get(AuthFixtures.ALPHA, overdue, AS_OF);
        assertThat(before.version()).isEqualTo(1); assertThat(before.nextActionDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(audits()).isEqualTo(1); assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).hasSize(1);
        long version = 1;
        for (String date : List.of("0001-01-01", "1500-03-01", "1582-10-10", "9999-12-31")) {
            mvc.perform(post("/api/collections/" + overdue + "/actions").session(alpha).with(csrf().asHeader()).contentType("application/json")
                    .content(noteBody(version++, "日付境界の予定", date))).andExpect(status().isOk()).andExpect(jsonPath("$.nextActionDate").value(date));
        }
    }

    @Test
    void csrfTenantStaleVersionAndDisabledActorsCannotChangeSharedState() throws Exception {
        mvc.perform(post("/api/collections/" + overdue + "/actions").session(alpha).contentType("application/json").content(noteBody(0, "拒否", null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/collections/" + foreign + "/actions").session(alpha).with(csrf().asHeader()).contentType("application/json").content(noteBody(0, "越境", null)))
                .andExpect(status().isNotFound());
        actions.record(user(), overdue, 0, "元の記録", null);
        mvc.perform(post("/api/collections/" + overdue + "/hold").session(alpha).with(csrf().asHeader()).contentType("application/json")
                .content("{\"version\":0,\"reason\":\"古い版\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        jdbc.sql("UPDATE app_user SET enabled=false WHERE tenant_id=? AND id=?").params(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ACCOUNTANT).update();
        try { assertThatThrownBy(() -> actions.hold(user(), overdue, 1, "無効化後の操作")).isInstanceOf(AccessDeniedException.class);
        } finally { jdbc.sql("UPDATE app_user SET enabled=true WHERE tenant_id=? AND id=?").params(AuthFixtures.ALPHA, AuthFixtures.ALPHA_ACCOUNTANT).update(); }
        assertThat(audits()).isEqualTo(1); assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).version()).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackStateEventAndLazyInitializationTogether() {
        jdbc.sql("CREATE FUNCTION reject_collection_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.action LIKE 'COLLECTION_%' THEN RAISE EXCEPTION 'Fictional collection audit failure'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_collection_audit_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_collection_audit_fixture()").update();
        try {
            assertThatThrownBy(() -> actions.record(user(), overdue, 0, "監査rollback確認", LocalDate.of(2026, 12, 5))).isInstanceOf(DataAccessException.class);
            assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).version()).isZero();
            assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).isEmpty(); assertThat(audits()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER reject_collection_audit_fixture ON audit_event").update(); jdbc.sql("DROP FUNCTION reject_collection_audit_fixture()").update();
        }
    }

    @Test
    void concurrentNotesWithTheSameVersionHaveOneWinnerOneEventAndOneAudit() throws Exception {
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<CollectionQueries.Event>>of(() -> actions.record(user(), overdue, 0, "担当Aの記録", LocalDate.of(2026, 12, 5)),
                    () -> actions.record(user(), overdue, 0, "担当Bの記録", LocalDate.of(2026, 12, 6))).stream().map(task -> pool.submit(() -> {
                        try { return (Object) transaction.execute(state -> {
                            ready.countDown();
                            try { if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Fixture start timed out"); } return task.call();
                            } catch (RuntimeException failure) { throw failure;
                            } catch (Exception failure) { throw new IllegalStateException(failure); }
                        }); } catch (CollectionActions.Conflict conflict) { return conflict; }
                    })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = List.of(tasks.getFirst().get(10, TimeUnit.SECONDS), tasks.getLast().get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(CollectionQueries.Event.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(CollectionActions.Conflict.class::isInstance).map(CollectionActions.Conflict.class::cast)).singleElement()
                    .extracting(Throwable::getMessage).isEqualTo("VERSION_CONFLICT");
        }
        assertThat(audits()).isEqualTo(1); assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).hasSize(1);
    }

    @Test
    void draftUsesCurrentExactOutstandingAndHeldReasonWithoutSendingOrInventingHistory() throws Exception {
        allocate(39560, 440);
        actions.hold(user(), overdue, 0, "入金確認待ち");
        long before = audits();
        var draft = drafts.preview(AuthFixtures.ALPHA, overdue, AS_OF);
        assertThat(draft.body()).contains("架空の回収先", "2026-11-30", "2026-12-01", "60,000 円", draft.facts().invoiceNumber());
        assertThat(draft.facts().held()).isTrue(); assertThat(draft.facts().holdReason()).isEqualTo("入金確認待ち");
        mvc.perform(get("/api/collections/" + overdue + "/draft?asOf=2026-12-01").session(alpha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingAmount").value(60000)).andExpect(jsonPath("$.holdReason").value("入金確認待ち"));
        assertThat(audits()).isEqualTo(before);
        allocate(60000, 0);
        mvc.perform(get("/api/collections/" + overdue + "/draft?asOf=2026-12-01").session(alpha)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_NOT_ELIGIBLE"));
        actions.record(user(), overdue, 1, "完済後の確認メモ", null);
        assertThat(queries.history(AuthFixtures.ALPHA, overdue, 0, 100).items()).hasSize(2);
        assertThat(queries.get(AuthFixtures.ALPHA, overdue, AS_OF).eligible()).isFalse();
    }

    private TenantUser user() { return TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow()); }
    private long audits() { return jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id=? AND entity_id=? AND action LIKE 'COLLECTION_%'")
            .params(AuthFixtures.ALPHA, overdue).query(Long.class).single(); }
    private String noteBody(long version, String note, String date) {
        var fields = new java.util.LinkedHashMap<String, Object>(); fields.put("version", version); fields.put("note", note); fields.put("nextActionDate", date);
        return json.writeValueAsString(fields);
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
