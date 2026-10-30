package com.example.debt.importing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Tenant predicates are applied even when an opaque batch ID is already known. */
@Repository
public class ImportStore {
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final JsonMapper json = JsonMapper.builder().build();
    public record Error(int number, CsvError value) { }
    public record Snapshot(int recordNumber, int rowNumber, Object typed, java.util.Map<String, ?> references) { }

    public ImportStore(JdbcClient jdbc, JdbcTemplate batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    public void save(ImportBatch batch, CustomerCsv.Preview preview) {
        save(batch, preview.rows().stream().map(row -> new Snapshot(row.recordNumber(), row.rowNumber(), row, java.util.Map.of())).toList(),
                preview.errors());
    }

    public void save(ImportBatch batch, List<Snapshot> rows, List<CsvError> failures) {
        jdbc.sql("""
                INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,
                    created_by_user_id,created_at,expires_at,row_count,error_count)
                VALUES (:id,:tenant,:kind,:format,decode(:hash,'hex'),:status,:actor,:created,:expires,:rows,:errors)
                """).param("id", batch.id()).param("tenant", batch.tenantId()).param("hash", batch.semanticFingerprint())
                .param("kind", batch.kind().name()).param("format", batch.formatVersion())
                .param("status", batch.status().name()).param("actor", batch.createdByUserId())
                .param("created", java.sql.Timestamp.from(batch.createdAt())).param("expires", java.sql.Timestamp.from(batch.expiresAt()))
                .param("rows", batch.rowCount()).param("errors", batch.errorCount()).update();
        batches.batchUpdate("""
                INSERT INTO import_row(tenant_id,batch_id,record_number,physical_start_line,typed_snapshot,reference_snapshot)
                VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """, rows, 500, (statement, row) -> {
                    statement.setObject(1, batch.tenantId()); statement.setObject(2, batch.id());
                    statement.setInt(3, row.recordNumber()); statement.setInt(4, row.rowNumber());
                    statement.setString(5, json.writeValueAsString(row.typed()));
                    statement.setString(6, json.writeValueAsString(row.references()));
                });
        List<Error> errors = java.util.stream.IntStream.range(0, failures.size())
                .mapToObj(index -> new Error(index + 1, failures.get(index))).toList();
        saveErrors(batch, errors);
    }

    public void saveErrors(ImportBatch batch, List<Error> errors) {
        batches.batchUpdate("""
                INSERT INTO import_error(tenant_id,batch_id,error_number,row_number,field,input_value,input_truncated,
                    reason_code,reason,correction) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, errors, 500, (statement, entry) -> {
                    CsvError error = entry.value();
                    statement.setObject(1, batch.tenantId()); statement.setObject(2, batch.id());
                    statement.setInt(3, entry.number()); statement.setInt(4, error.rowNumber());
                    statement.setString(5, error.field()); statement.setString(6, error.inputValue());
                    statement.setBoolean(7, error.inputTruncated()); statement.setString(8, error.reasonCode());
                    statement.setString(9, error.reason()); statement.setString(10, error.correction());
                });
    }

    public UUID confirmedDuplicate(ImportBatch batch) {
        return jdbc.sql("""
                SELECT id FROM import_batch WHERE tenant_id=:tenant AND kind=:kind AND format_version=:format
                AND semantic_fingerprint=decode(:hash,'hex') AND status='CONFIRMED' AND id<>:id
                """).param("tenant", batch.tenantId()).param("kind", batch.kind().name()).param("format", batch.formatVersion())
                .param("hash", batch.semanticFingerprint()).param("id", batch.id()).query(UUID.class).optional().orElse(null);
    }

    public Set<Integer> invalidLines(UUID tenant, UUID id, List<Integer> lines) {
        return lines.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("""
                SELECT DISTINCT row_number FROM import_error WHERE tenant_id=:tenant AND batch_id=:id AND row_number IN (:lines)
                """).param("tenant", tenant).param("id", id).param("lines", lines).query(Integer.class).list());
    }

    public ImportBatch get(UUID tenant, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT *,encode(semantic_fingerprint,'hex') AS fingerprint FROM import_batch
                WHERE tenant_id=:tenant AND id=:id AND kind IN ('CUSTOMER','INVOICE')
                """ + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenant).param("id", id).query(this::batch).optional().orElseThrow(ImportNotFound::new);
    }

    public List<CustomerCsv.Row> rows(UUID tenant, UUID id, int cursor, int limit) {
        return jdbc.sql("""
                SELECT typed_snapshot::text AS snapshot FROM import_row
                WHERE tenant_id=:tenant AND batch_id=:id AND record_number>:cursor ORDER BY record_number LIMIT :limit
                """).param("tenant", tenant).param("id", id).param("cursor", cursor).param("limit", limit)
                .query((result, number) -> json.readValue(result.getString("snapshot"), CustomerCsv.Row.class)).list();
    }

    public List<InvoiceCsv.Row> invoiceRows(UUID tenant, UUID id, int cursor, int limit) {
        return jdbc.sql("""
                SELECT typed_snapshot::text AS snapshot FROM import_row
                WHERE tenant_id=:tenant AND batch_id=:id AND record_number>:cursor ORDER BY record_number LIMIT :limit
                """).param("tenant", tenant).param("id", id).param("cursor", cursor).param("limit", limit)
                .query((result, number) -> json.readValue(result.getString("snapshot"), InvoiceCsv.Row.class)).list();
    }

    public List<Error> errors(UUID tenant, UUID id, int cursor, int limit) {
        return jdbc.sql("""
                SELECT * FROM import_error WHERE tenant_id=:tenant AND batch_id=:id AND error_number>:cursor
                ORDER BY error_number LIMIT :limit
                """).param("tenant", tenant).param("id", id).param("cursor", cursor).param("limit", limit)
                .query((result, number) -> new Error(result.getInt("error_number"), new CsvError(result.getInt("row_number"),
                        result.getString("field"), result.getString("input_value"), result.getBoolean("input_truncated"),
                        result.getString("reason_code"), result.getString("reason"), result.getString("correction")))).list();
    }

    private ImportBatch batch(ResultSet result, int number) throws SQLException {
        return new ImportBatch(result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class), ImportBatch.Kind.valueOf(result.getString("kind")),
                result.getInt("format_version"), result.getString("fingerprint"), ImportBatch.Status.valueOf(result.getString("status")),
                result.getLong("version"), result.getObject("created_by_user_id", UUID.class), instant(result, "created_at"),
                instant(result, "expires_at"), result.getObject("confirmed_by_user_id", UUID.class), instant(result, "confirmed_at"),
                result.getInt("row_count"), result.getInt("error_count"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        var timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
