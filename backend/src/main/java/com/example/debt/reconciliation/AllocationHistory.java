package com.example.debt.reconciliation;

import com.example.debt.payment.PaymentValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A single statement joins historical decisions and current display/version information. */
@Repository
public class AllocationHistory {
    public record Line(UUID invoiceId, String invoiceNumber, UUID customerId, String customerName,
                       long observedInvoiceVersion, long currentInvoiceVersion, long cash, long fee) { }
    public record Entry(UUID id, UUID requestId, String kind, UUID reversesId, UUID reversedByOperationId,
                        long paymentVersion, UUID actorUserId, String actorEmail, String reason, Instant occurredAt, List<Line> lines) { }
    public record Page(UUID paymentId, long paymentVersion, List<Entry> items, String nextCursor) { }
    private record Flat(long paymentVersion, Entry entry, Line line) { }
    private final JdbcClient jdbc;
    public AllocationHistory(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page list(UUID tenant, UUID payment, String cursor, int limit) {
        if (cursor == null || cursor.length() > 19 || !cursor.matches("0|[1-9][0-9]*") || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid allocation history page");
        }
        long after = Long.parseLong(cursor);
        var rows = jdbc.sql("""
                SELECT COALESCE(s.version,0) AS current_payment_version,o.id,o.request_id,o.kind,o.reverses_id,
                    r.id AS reversed_by,o.payment_version,o.actor_user_id,u.email_canonical,o.reason,o.occurred_at,
                    l.invoice_id,l.invoice_version,l.cash_jpy,l.fee_jpy,i.invoice_number,i.customer_id,
                    i.version AS current_invoice_version,c.name AS customer_name
                FROM payment p
                LEFT JOIN reconciliation_payment_state s ON s.tenant_id=p.tenant_id AND s.payment_id=p.id
                LEFT JOIN LATERAL (
                    SELECT operation.* FROM allocation_operation operation
                    WHERE operation.tenant_id=p.tenant_id AND operation.payment_id=p.id AND operation.payment_version>:after
                    ORDER BY operation.payment_version LIMIT :limit
                ) o ON true
                LEFT JOIN app_user u ON u.tenant_id=p.tenant_id AND u.id=o.actor_user_id
                LEFT JOIN allocation_operation r ON r.tenant_id=p.tenant_id AND r.payment_id=p.id AND r.reverses_id=o.id
                LEFT JOIN allocation_line l ON l.tenant_id=p.tenant_id AND l.operation_id=CASE WHEN o.kind='REVERSE' THEN o.reverses_id ELSE o.id END
                LEFT JOIN invoice i ON i.tenant_id=p.tenant_id AND i.id=l.invoice_id
                LEFT JOIN customer c ON c.tenant_id=p.tenant_id AND c.id=i.customer_id
                WHERE p.tenant_id=:tenant AND p.id=:payment
                ORDER BY o.payment_version,l.invoice_id
                """).param("tenant", tenant).param("payment", payment).param("after", after).param("limit", limit + 1)
                .query(this::flat).list();
        if (rows.isEmpty()) { throw new AllocationCommands.Missing(); }
        var entries = new LinkedHashMap<UUID, Entry>();
        for (Flat row : rows) {
            if (row.entry() != null) {
                Entry entry = entries.computeIfAbsent(row.entry().id(), id -> row.entry());
                entry.lines().add(row.line());
            }
        }
        List<Entry> items = entries.values().stream().limit(limit).map(entry -> new Entry(entry.id(), entry.requestId(), entry.kind(),
                entry.reversesId(), entry.reversedByOperationId(), entry.paymentVersion(), entry.actorUserId(), entry.actorEmail(), entry.reason(),
                entry.occurredAt(), List.copyOf(entry.lines()))).toList();
        return new Page(payment, rows.getFirst().paymentVersion(), items,
                entries.size() > limit ? Long.toString(items.getLast().paymentVersion()) : null);
    }

    private Flat flat(ResultSet result, int row) throws SQLException {
        long current = result.getLong("current_payment_version");
        UUID id = result.getObject("id", UUID.class);
        if (id == null) { return new Flat(current, null, null); }
        Entry entry = new Entry(id, result.getObject("request_id", UUID.class), result.getString("kind"),
                result.getObject("reverses_id", UUID.class), result.getObject("reversed_by", UUID.class), result.getLong("payment_version"),
                result.getObject("actor_user_id", UUID.class), result.getString("email_canonical"), result.getString("reason"),
                result.getTimestamp("occurred_at").toInstant(), new ArrayList<>());
        Line line = new Line(result.getObject("invoice_id", UUID.class), result.getString("invoice_number"),
                result.getObject("customer_id", UUID.class), result.getString("customer_name"), result.getLong("invoice_version"),
                result.getLong("current_invoice_version"), money(result, "cash_jpy"), money(result, "fee_jpy"));
        return new Flat(current, entry, line);
    }
    private long money(ResultSet result, String column) throws SQLException {
        long value = result.getBigDecimal(column).longValueExact();
        if (value < 0 || value > PaymentValues.MAX_AMOUNT_JPY) { throw new IllegalStateException("Allocation history amount outside public JPY range"); }
        return value;
    }
}
