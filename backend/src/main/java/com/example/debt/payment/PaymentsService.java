package com.example.debt.payment;

import com.example.debt.reconciliation.LedgerReadAmounts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PaymentsService {
    private final JdbcClient jdbc;
    public record Row(UUID id, String transactionId, LocalDate transferDate, String payerName, long amount, long unallocated,
                      UUID sourceBatchId, UUID createdByUserId, Instant createdAt) { }
    public record Page(List<Row> items, String nextCursor) { }

    public PaymentsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page list(UUID tenant, LocalDate from, LocalDate to, String cursor, int limit) {
        if (limit < 1 || limit > 100 || from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid payment page bounds");
        }
        Map<String, Object> parameters = new HashMap<>(Map.of("tenant", tenant, "limit", limit + 1));
        StringBuilder sql = new StringBuilder("SELECT p.*,a.cash FROM payment p " + LedgerReadAmounts.PAYMENT_JOIN + " WHERE p.tenant_id=:tenant");
        if (from != null) { sql.append(" AND transfer_date>=:from"); parameters.put("from", PaymentValues.date(from.toString())); }
        if (to != null) { sql.append(" AND transfer_date<=:to"); parameters.put("to", PaymentValues.date(to.toString())); }
        if (cursor != null && !cursor.isEmpty()) {
            if (cursor.length() != 47 || cursor.charAt(10) != ':') { throw new IllegalArgumentException("Invalid payment cursor"); }
            LocalDate date = PaymentValues.date(cursor.substring(0, 10));
            UUID id = UUID.fromString(cursor.substring(11));
            if (!id.toString().equals(cursor.substring(11))) { throw new IllegalArgumentException("Invalid payment cursor"); }
            sql.append(" AND (transfer_date,id)>(:date,:id)"); parameters.put("date", date); parameters.put("id", id);
        }
        sql.append(" ORDER BY transfer_date,id LIMIT :limit");
        var rows = jdbc.sql(sql.toString()).params(parameters).query((result, number) -> new Row(result.getObject("id", UUID.class),
                result.getString("transaction_id"), result.getObject("transfer_date", LocalDate.class), result.getString("payer_name"),
                result.getLong("amount_jpy"), LedgerReadAmounts.unallocated(result), result.getObject("source_batch_id", UUID.class), result.getObject("created_by_user_id", UUID.class),
                result.getTimestamp("created_at").toInstant())).list();
        List<Row> page = rows.stream().limit(limit).toList();
        return new Page(page, rows.size() > limit ? page.getLast().transferDate() + ":" + page.getLast().id() : null);
    }
}
