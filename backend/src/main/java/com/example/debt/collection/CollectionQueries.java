package com.example.debt.collection;

import com.example.debt.invoice.InvoiceValues;
import com.example.debt.reconciliation.LedgerBalances;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionQueries {
    private final JdbcClient jdbc;
    public enum View { ACTIONABLE, HELD, ALL }
    public record Case(UUID invoiceId, UUID customerId, String customerCode, String customerName, String invoiceNumber,
                       LocalDate issueDate, LocalDate dueDate, String invoiceStatus, long invoiceVersion,
                       LedgerBalances.Invoice balance, LocalDate asOf, int daysOverdue, boolean eligible,
                       boolean held, String holdReason, String latestNote, LocalDate nextActionDate, long version) { }
    public record Page(List<Case> items, String nextCursor) { }
    public record Event(long version, String kind, String note, String reason, UUID actorUserId, String actorLabel,
                        Instant occurredAt, boolean held, String holdReason, LocalDate nextActionDate) { }
    public record History(List<Event> items, Long nextCursor) { }
    public static class Missing extends RuntimeException { }
    private static final String SELECT = """
            SELECT i.*,c.code,c.name,COALESCE(s.version,0) AS collection_version,COALESCE(s.held,false) AS held,
                s.hold_reason,s.latest_note,s.next_action_date
            FROM reconciliation_invoice_balance i JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
            LEFT JOIN collection_state s ON s.tenant_id=i.tenant_id AND s.invoice_id=i.id
            WHERE i.tenant_id=:tenant
            """;

    public CollectionQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page list(UUID tenant, LocalDate asOf, View view, String cursor, int limit) {
        CollectionValues.date(asOf);
        if (limit < 1 || limit > 100 || view == null) { throw new IllegalArgumentException("Invalid collection page"); }
        var sql = new StringBuilder(SELECT).append(" AND i.status='ACTIVE' AND i.due_date<:asOf AND i.amount_jpy-i.active_cash_jpy-i.active_fee_jpy>0");
        Map<String, Object> parameters = new HashMap<>(Map.of("tenant", tenant, "asOf", asOf, "limit", limit + 1));
        if (view != View.ALL) { sql.append(" AND COALESCE(s.held,false)=:held"); parameters.put("held", view == View.HELD); }
        if (cursor != null && !cursor.isEmpty()) {
            if (cursor.length() != 47 || cursor.charAt(10) != ':') { throw new IllegalArgumentException("Invalid collection cursor"); }
            LocalDate due = InvoiceValues.parseDate(cursor.substring(0, 10), "cursor");
            UUID id = UUID.fromString(cursor.substring(11));
            if (!id.toString().equals(cursor.substring(11))) { throw new IllegalArgumentException("Invalid collection cursor"); }
            sql.append(" AND (i.due_date,i.id)>(:due,:id)"); parameters.put("due", due); parameters.put("id", id);
        }
        var rows = jdbc.sql(sql.append(" ORDER BY i.due_date,i.id LIMIT :limit").toString()).params(parameters)
                .query((result, row) -> row(result, asOf)).list();
        var page = rows.stream().limit(limit).toList();
        return new Page(page, rows.size() > limit ? page.getLast().dueDate() + ":" + page.getLast().invoiceId() : null);
    }

    public Case get(UUID tenant, UUID invoice, LocalDate asOf) {
        CollectionValues.date(asOf);
        return jdbc.sql(SELECT + " AND i.id=:id").param("tenant", tenant).param("id", invoice)
                .query((result, index) -> row(result, asOf)).optional().orElseThrow(Missing::new);
    }

    public History history(UUID tenant, UUID invoice, long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid collection history page"); }
        if (jdbc.sql("SELECT id FROM invoice WHERE tenant_id=? AND id=?").params(tenant, invoice).query(UUID.class).optional().isEmpty()) { throw new Missing(); }
        var rows = jdbc.sql("SELECT * FROM collection_event WHERE tenant_id=? AND invoice_id=? AND version>? ORDER BY version LIMIT ?")
                .params(tenant, invoice, cursor, limit + 1).query((result, index) -> new Event(result.getLong("version"), result.getString("kind"),
                        result.getString("note"), result.getString("reason"), result.getObject("actor_user_id", UUID.class), result.getString("actor_label"),
                        result.getTimestamp("occurred_at").toInstant(), result.getBoolean("held"), result.getString("hold_reason"),
                        result.getObject("next_action_date", LocalDate.class))).list();
        var page = rows.stream().limit(limit).toList();
        return new History(page, rows.size() > limit ? page.getLast().version() : null);
    }

    private Case row(ResultSet result, LocalDate asOf) throws SQLException {
        var balance = new LedgerBalances.Invoice(BigInteger.valueOf(result.getLong("amount_jpy")),
                result.getBigDecimal("active_cash_jpy").toBigIntegerExact(), result.getBigDecimal("active_fee_jpy").toBigIntegerExact());
        LocalDate due = result.getObject("due_date", LocalDate.class);
        boolean eligible = result.getString("status").equals("ACTIVE") && due.isBefore(asOf) && balance.outstanding().signum() > 0;
        return new Case(result.getObject("id", UUID.class), result.getObject("customer_id", UUID.class), result.getString("code"), result.getString("name"),
                result.getString("invoice_number"), result.getObject("issue_date", LocalDate.class), due, result.getString("status"), result.getLong("version"),
                balance, asOf, CollectionValues.daysOverdue(due, asOf), eligible, result.getBoolean("held"), result.getString("hold_reason"),
                result.getString("latest_note"), result.getObject("next_action_date", LocalDate.class), result.getLong("collection_version"));
    }
}
