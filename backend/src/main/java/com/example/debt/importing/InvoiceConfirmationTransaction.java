package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import com.example.debt.invoice.InvoiceValues;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InvoiceConfirmationTransaction {
    private final ImportStore store;
    private final InvoiceImportService previews;
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private record Created(UUID id, InvoiceCsv.Row row, InvoiceValues values) { }

    public InvoiceConfirmationTransaction(ImportStore store, InvoiceImportService previews, JdbcClient jdbc,
            JdbcTemplate batches, @Qualifier("importClock") Clock clock) {
        this.store = store; this.previews = previews; this.jdbc = jdbc; this.batches = batches; this.clock = clock;
    }

    @Transactional
    public ImportBatch confirm(TenantUser user, UUID id, long expectedVersion) {
        ImportBatch batch = store.get(user.tenantId(), id, true);
        if (batch.kind() != ImportBatch.Kind.INVOICE) { throw new ImportRejected("IMPORT_KIND_UNSUPPORTED"); }
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
        List<ImportStore.InvoiceSnapshot> rows = store.invoiceSnapshots(user.tenantId(), id);
        if (rows.size() != batch.rowCount() || rows.isEmpty() || rows.stream().anyMatch(snapshot -> !snapshot.row().valid()
                || snapshot.customerId() == null || snapshot.customerVersion() == null)) {
            throw new IllegalStateException("Stored invoice snapshot counters or references are inconsistent");
        }
        // Lock the observed customers in one deterministic order and hold their versions until commit.
        var customerIds = rows.stream().map(ImportStore.InvoiceSnapshot::customerId).collect(Collectors.toSet());
        Map<UUID, Long> versions = jdbc.sql("SELECT id,version FROM customer WHERE tenant_id=:tenant AND id IN (:ids) ORDER BY id FOR SHARE")
                .param("tenant", user.tenantId()).param("ids", customerIds)
                .query((result, number) -> Map.entry(result.getObject("id", UUID.class), result.getLong("version")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<CsvError> conflicts = new ArrayList<>();
        for (var snapshot : rows) {
            if (!snapshot.customerVersion().equals(versions.get(snapshot.customerId()))) {
                conflicts.add(new CsvError(snapshot.row().rowNumber(), "customer_code", snapshot.row().customerCode(), false,
                        "CUSTOMER_CHANGED", "プレビュー後に顧客情報が変更されました。", "新しいプレビューで顧客と請求内容を確認してください。"));
            }
        }
        var existing = previews.existingNumbers(user.tenantId(), rows.stream().map(snapshot -> snapshot.row().invoiceNumber()).collect(Collectors.toSet()));
        for (var snapshot : rows) {
            if (existing.contains(snapshot.row().invoiceNumber())) {
                conflicts.add(new CsvError(snapshot.row().rowNumber(), "invoice_number", snapshot.row().invoiceNumber(), false,
                        "INVOICE_EXISTS", "プレビュー後に請求番号が登録されました。", "請求番号を確認し、新しいプレビューを作成してください。"));
            }
        }
        if (!conflicts.isEmpty()) { throw new ImportRejected("IMPORT_REFERENCE_CONFLICT", null, conflicts); }
        List<Created> created = rows.stream().sorted(Comparator.comparing(snapshot -> snapshot.row().invoiceNumber()))
                .map(snapshot -> new Created(UUID.randomUUID(), snapshot.row(), new InvoiceValues(snapshot.customerId(),
                        snapshot.row().invoiceNumber(), InvoiceValues.parseDate(snapshot.row().issueDate(), "issue_date"),
                        InvoiceValues.parseDate(snapshot.row().dueDate(), "due_date"), InvoiceValues.parseAmount(snapshot.row().amount())))).toList();
        batches.batchUpdate("""
                INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, created, 500, (statement, invoice) -> {
                    statement.setObject(1, invoice.id()); statement.setObject(2, user.tenantId());
                    statement.setObject(3, invoice.values().customerId()); statement.setString(4, invoice.values().invoiceNumber());
                    statement.setObject(5, invoice.values().issueDate()); statement.setObject(6, invoice.values().dueDate());
                    statement.setLong(7, invoice.values().amountJpy()); statement.setObject(8, id);
                });
        var now = clock.instant();
        if (!now.isBefore(batch.expiresAt())) { throw new ImportRejected("IMPORT_EXPIRED"); }
        batches.batchUpdate("""
                INSERT INTO invoice_revision(tenant_id,invoice_id,revision,operation,snapshot,reason,actor_user_id,occurred_at)
                VALUES (?,?,0,'CREATED',CAST(? AS jsonb),NULL,?,?)
                """, created, 500, (statement, invoice) -> {
                    statement.setObject(1, user.tenantId()); statement.setObject(2, invoice.id());
                    statement.setString(3, snapshot(invoice, id)); statement.setObject(4, user.userId());
                    statement.setTimestamp(5, Timestamp.from(now));
                });
        batches.batchUpdate("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,'INVOICE_CREATED',?,CAST(? AS jsonb))
                """, created, 500, (statement, invoice) -> {
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, user.tenantId());
                    statement.setString(3, "user:" + user.userId()); statement.setObject(4, user.userId());
                    statement.setObject(5, invoice.id()); statement.setString(6, snapshot(invoice, id));
                });
        var confirmedAt = clock.instant();
        if (!confirmedAt.isBefore(batch.expiresAt())) { throw new ImportRejected("IMPORT_EXPIRED"); }
        jdbc.sql("""
                UPDATE import_batch SET status='CONFIRMED',version=version+1,confirmed_by_user_id=:actor,confirmed_at=:now
                WHERE tenant_id=:tenant AND id=:id AND version=:version
                """).param("actor", user.userId()).param("now", Timestamp.from(confirmedAt)).param("tenant", user.tenantId())
                .param("id", id).param("version", expectedVersion).update();
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,'INVOICE_IMPORT_CONFIRMED',?,CAST(? AS jsonb))
                """).params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(), id,
                        json.writeValueAsString(Map.of("rowCount", created.size()))).update();
        return store.get(user.tenantId(), id, false);
    }

    private String snapshot(Created invoice, UUID batch) {
        return json.writeValueAsString(Map.of("customerId", invoice.values().customerId(), "customerCode", invoice.row().customerCode(),
                "customerName", invoice.row().customerName(), "invoiceNumber", invoice.values().invoiceNumber(),
                "issueDate", invoice.values().issueDate(), "dueDate", invoice.values().dueDate(), "amount", invoice.values().amountJpy(),
                "status", "ACTIVE", "sourceBatchId", batch, "version", 0));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(TenantUser user, UUID id, long version, List<CsvError> errors) {
        ImportBatch batch = store.get(user.tenantId(), id, true);
        if (batch.kind() != ImportBatch.Kind.INVOICE || batch.status() != ImportBatch.Status.PREVIEW_VALID
                || batch.version() != version || errors.isEmpty()) { return; }
        var entries = java.util.stream.IntStream.range(0, errors.size()).mapToObj(index -> new ImportStore.Error(index + 1, errors.get(index))).toList();
        store.saveErrors(batch, entries);
        jdbc.sql("UPDATE import_batch SET status='PREVIEW_INVALID',version=version+1,error_count=:errors WHERE tenant_id=:tenant AND id=:id AND version=:version")
                .param("errors", entries.size()).param("tenant", user.tenantId()).param("id", id).param("version", version).update();
    }

    ImportRejected duplicate(UUID original) {
        return new ImportRejected("IMPORT_ALREADY_CONFIRMED", original, List.of(new CsvError(1, "file", "", false,
                "IMPORT_ALREADY_CONFIRMED", "同じ内容のCSVは取込済みです。", "元の取込結果を確認してください。")));
    }
}
