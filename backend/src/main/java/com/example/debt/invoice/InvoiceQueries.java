package com.example.debt.invoice;

import com.example.debt.reconciliation.LedgerReadAmounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InvoiceQueries {
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public record Revision(long revision, String operation, String reason, UUID actorUserId, Instant occurredAt,
                           InvoiceState.Facts snapshot, String actorEmail) { }
    public record View(InvoiceState invoice, long outstanding) { }
    public record History(List<Revision> items, Long nextCursor) { }

    public InvoiceQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public InvoiceState get(UUID tenant, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT i.*,c.code,c.name FROM invoice i JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
                WHERE i.tenant_id=:tenant AND i.id=:id
                """ + (lock ? " FOR UPDATE OF i" : "")).param("tenant", tenant).param("id", id)
                .query((result, row) -> state(result)).optional().orElseThrow(InvoiceNotFound::new);
    }

    public View view(UUID tenant, UUID id) {
        return jdbc.sql("""
                SELECT i.*,c.code,c.name,a.cash,a.fee FROM invoice i
                JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
                """ + LedgerReadAmounts.INVOICE_JOIN + " WHERE i.tenant_id=:tenant AND i.id=:id")
                .param("tenant", tenant).param("id", id)
                .query((result, row) -> new View(state(result), LedgerReadAmounts.outstanding(result)))
                .optional().orElseThrow(InvoiceNotFound::new);
    }

    private InvoiceState state(ResultSet result) throws SQLException {
        return new InvoiceState(result.getObject("id", UUID.class), result.getObject("customer_id", UUID.class),
                result.getString("code"), result.getString("name"), result.getString("invoice_number"),
                result.getObject("issue_date", LocalDate.class), result.getObject("due_date", LocalDate.class), result.getLong("amount_jpy"),
                result.getLong("version"), result.getString("status"), result.getObject("source_batch_id", UUID.class),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                result.getTimestamp("cancelled_at") == null ? null : result.getTimestamp("cancelled_at").toInstant());
    }

    public History history(UUID tenant, UUID id, long cursor, int limit) {
        get(tenant, id, false);
        if (cursor < -1 || limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid invoice history page"); }
        var rows = jdbc.sql("""
                SELECT r.*,u.email_canonical AS actor_email FROM invoice_revision r
                JOIN app_user u ON u.tenant_id=r.tenant_id AND u.id=r.actor_user_id
                WHERE r.tenant_id=:tenant AND r.invoice_id=:id AND r.revision>:cursor
                ORDER BY r.revision LIMIT :limit
                """).param("tenant", tenant).param("id", id).param("cursor", cursor).param("limit", limit + 1)
                .query((result, row) -> new Revision(result.getLong("revision"), result.getString("operation"), result.getString("reason"),
                        result.getObject("actor_user_id", UUID.class), result.getTimestamp("occurred_at").toInstant(),
                        json.readValue(result.getString("snapshot"), InvoiceState.Facts.class), result.getString("actor_email"))).list();
        var page = rows.stream().limit(limit).toList();
        return new History(page, rows.size() > limit ? page.getLast().revision() : null);
    }
}
