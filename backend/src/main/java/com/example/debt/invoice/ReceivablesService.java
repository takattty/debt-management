package com.example.debt.invoice;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReceivablesService {
    private final JdbcClient jdbc;
    public record Row(UUID id, UUID customerId, String customerCode, String customerName, String invoiceNumber,
                      LocalDate issueDate, LocalDate dueDate, long amount, long version) { }
    public record Page(List<Row> items, String nextCursor) { }

    public ReceivablesService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page list(UUID tenant, UUID customer, LocalDate from, LocalDate to, String cursor, int limit) {
        if (limit < 1 || limit > 100 || from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid receivable page bounds");
        }
        Map<String, Object> parameters = new HashMap<>(Map.of("tenant", tenant, "limit", limit + 1));
        StringBuilder sql = new StringBuilder("""
                SELECT i.id,i.customer_id,c.code,c.name,i.invoice_number,i.issue_date,i.due_date,i.amount_jpy,i.version
                FROM invoice i JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
                WHERE i.tenant_id=:tenant AND i.status='ACTIVE'
                """);
        if (customer != null) { sql.append(" AND i.customer_id=:customer"); parameters.put("customer", customer); }
        if (from != null) { sql.append(" AND i.due_date>=:from"); parameters.put("from", from); }
        if (to != null) { sql.append(" AND i.due_date<=:to"); parameters.put("to", to); }
        if (cursor != null && !cursor.isEmpty()) {
            if (cursor.length() != 47 || cursor.charAt(10) != ':') { throw new IllegalArgumentException("Invalid receivable cursor"); }
            LocalDate due = InvoiceValues.parseDate(cursor.substring(0, 10), "cursor");
            UUID id = UUID.fromString(cursor.substring(11));
            if (!id.toString().equals(cursor.substring(11))) { throw new IllegalArgumentException("Invalid receivable cursor"); }
            sql.append(" AND (i.due_date,i.id)>(:cursorDue,:cursorId)"); parameters.put("cursorDue", due); parameters.put("cursorId", id);
        }
        sql.append(" ORDER BY i.due_date,i.id LIMIT :limit");
        var rows = jdbc.sql(sql.toString()).params(parameters).query((result, number) -> new Row(result.getObject("id", UUID.class),
                result.getObject("customer_id", UUID.class), result.getString("code"), result.getString("name"), result.getString("invoice_number"),
                result.getObject("issue_date", LocalDate.class), result.getObject("due_date", LocalDate.class), result.getLong("amount_jpy"), result.getLong("version"))).list();
        List<Row> page = rows.stream().limit(limit).toList();
        String next = rows.size() > limit ? page.getLast().dueDate() + ":" + page.getLast().id() : null;
        return new Page(page, next);
    }
}
