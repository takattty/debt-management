package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import com.example.debt.payment.PaymentValues;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Invoked through a separate Spring bean so rollback finishes before conflict recording starts. */
@Service
public class PaymentConfirmationTransaction {
    private final ImportStore store;
    private final PaymentImportService preview;
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private record Created(UUID id, PaymentValues values) { }

    public PaymentConfirmationTransaction(ImportStore store, PaymentImportService preview, JdbcClient jdbc,
            JdbcTemplate batches, @Qualifier("importClock") Clock clock) {
        this.store = store; this.preview = preview; this.jdbc = jdbc; this.batches = batches; this.clock = clock;
    }

    @Transactional
    public ImportBatch confirm(TenantUser user, UUID id, long expectedVersion) {
        ImportBatch batch = store.get(user.tenantId(), id, true);
        if (batch.kind() != ImportBatch.Kind.PAYMENT) { throw new ImportRejected("IMPORT_KIND_UNSUPPORTED"); }
        if (batch.status() == ImportBatch.Status.CONFIRMED
                && (expectedVersion == batch.version() - 1 || expectedVersion == batch.version())) { return batch; }
        if (batch.version() != expectedVersion) { throw new ImportRejected("VERSION_CONFLICT"); }
        if (batch.isPreviewExpired(clock)) { throw new ImportRejected("IMPORT_EXPIRED"); }
        UUID original = store.confirmedDuplicate(batch);
        if (original != null) { throw duplicate(original); }
        if (batch.status() != ImportBatch.Status.PREVIEW_VALID) { throw new ImportRejected("IMPORT_INVALID"); }
        if (jdbc.sql("SELECT id FROM app_user WHERE tenant_id=? AND id=? AND enabled FOR SHARE")
                .params(user.tenantId(), user.userId()).query(UUID.class).optional().isEmpty()) {
            throw new AccessDeniedException("User membership changed");
        }
        List<PaymentCsv.Row> rows = store.paymentRows(user.tenantId(), id, 0, 10_001);
        if (rows.size() != batch.rowCount() || rows.isEmpty() || rows.stream().anyMatch(row -> !row.valid())) {
            throw new IllegalStateException("Stored import snapshot counters are inconsistent");
        }
        var existing = preview.existingIds(user.tenantId(), rows.stream().map(PaymentCsv.Row::transactionId)
                .collect(java.util.stream.Collectors.toSet()));
        List<CsvError> conflicts = rows.stream().filter(row -> existing.contains(row.transactionId()))
                .map(row -> new CsvError(row.rowNumber(), "transaction_id", row.transactionId(), false, "PAYMENT_EXISTS",
                        "プレビュー後に取引IDが登録されました。消込取消後も再利用できません。", "CSVを修正し、新しいプレビューを確認してください。")).toList();
        if (!conflicts.isEmpty()) { throw new ImportRejected("IMPORT_REFERENCE_CONFLICT", null, conflicts); }
        // Consistent unique-index acquisition prevents opposite CSV orders from deadlocking.
        List<Created> created = rows.stream().map(row -> new Created(UUID.randomUUID(), new PaymentValues(row.transactionId(),
                PaymentValues.date(row.transferDate()), row.payerName(), PaymentValues.amount(row.amount()))))
                .sorted(Comparator.comparing(payment -> payment.values().transactionId())).toList();
        var recordedAt = clock.instant();
        batches.batchUpdate("""
                INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, created, 500, (statement, payment) -> {
                    statement.setObject(1, payment.id()); statement.setObject(2, user.tenantId());
                    statement.setString(3, payment.values().transactionId()); statement.setDate(4, java.sql.Date.valueOf(payment.values().transferDate()));
                    statement.setString(5, payment.values().payerName()); statement.setLong(6, payment.values().amountJpy());
                    statement.setObject(7, id); statement.setObject(8, user.userId()); statement.setTimestamp(9, Timestamp.from(recordedAt));
                });
        batches.batchUpdate("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,'PAYMENT_CREATED',?,CAST(? AS jsonb))
                """, created, 500, (statement, payment) -> {
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, user.tenantId());
                    statement.setString(3, "user:" + user.userId()); statement.setObject(4, user.userId());
                    statement.setObject(5, payment.id()); statement.setString(6, json.writeValueAsString(Map.of(
                            "transactionId", payment.values().transactionId(), "transferDate", payment.values().transferDate(),
                            "payerName", payment.values().payerName(), "amount", payment.values().amountJpy(), "sourceBatchId", id)));
                });
        var now = clock.instant();
        if (!now.isBefore(batch.expiresAt())) { throw new ImportRejected("IMPORT_EXPIRED"); }
        jdbc.sql("""
                UPDATE import_batch SET status='CONFIRMED',version=version+1,confirmed_by_user_id=:actor,confirmed_at=:now
                WHERE tenant_id=:tenant AND id=:id AND version=:version
                """).param("actor", user.userId()).param("now", Timestamp.from(now)).param("tenant", user.tenantId())
                .param("id", id).param("version", expectedVersion).update();
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,'PAYMENT_IMPORT_CONFIRMED',?,CAST(? AS jsonb))
                """).params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(), id,
                        json.writeValueAsString(Map.of("rowCount", created.size()))).update();
        return store.get(user.tenantId(), id, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(TenantUser user, UUID id, long version, List<CsvError> errors) {
        ImportBatch batch = store.get(user.tenantId(), id, true);
        if (batch.kind() != ImportBatch.Kind.PAYMENT || batch.status() != ImportBatch.Status.PREVIEW_VALID || batch.version() != version || errors.isEmpty()) { return; }
        List<ImportStore.Error> entries = new ArrayList<>();
        for (int index = 0; index < errors.size(); index++) { entries.add(new ImportStore.Error(index + 1, errors.get(index))); }
        store.saveErrors(batch, entries);
        jdbc.sql("""
                UPDATE import_batch SET status='PREVIEW_INVALID',version=version+1,error_count=:errors
                WHERE tenant_id=:tenant AND id=:id AND version=:version
                """).param("errors", entries.size()).param("tenant", user.tenantId()).param("id", id).param("version", version).update();
    }

    ImportRejected duplicate(UUID original) {
        return new ImportRejected("IMPORT_ALREADY_CONFIRMED", original, List.of(new CsvError(1, "file", "", false,
                "IMPORT_ALREADY_CONFIRMED", "同じ内容のCSVは取込済みです。", "元の取込結果を確認してください。")));
    }
}
