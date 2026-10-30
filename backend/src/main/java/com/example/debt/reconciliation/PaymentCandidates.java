package com.example.debt.reconciliation;

import com.example.debt.payment.PaymentValues;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only ranking from V8 facts; never creates state, locks, commands, or audits. */
@Service
public class PaymentCandidates {
    private final JdbcClient jdbc;
    public record Payment(UUID id, String payerName, LocalDate transferDate, long unallocated, long version) { }
    public record Candidate(UUID invoiceId, UUID customerId, String customerCode, String customerName, String invoiceNumber,
                            LocalDate issueDate, LocalDate dueDate, long amount, long outstanding, long version,
                            boolean manualPayerMatch, boolean exactAmountMatch) { }
    public record Page(Payment payment, int matchedCustomers, List<Candidate> items, String nextCursor) { }
    private record Snapshot(Payment payment, int matchedCustomers, Candidate candidate) { }
    public static class Missing extends RuntimeException { }

    public PaymentCandidates(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Page list(UUID tenant, UUID payment, String cursor, int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid candidate page size"); }
        CandidateCursor after = CandidateCursor.parse(cursor, payment);
        // Only the immutable name is read before the financial snapshot, to use the CSV's shared text rule.
        String name = PaymentValues.text(jdbc.sql("SELECT payer_name FROM payment WHERE tenant_id=? AND id=?")
                .params(tenant, payment).query(String.class).optional().orElseThrow(Missing::new), 200);
        Map<String, Object> parameters = new HashMap<>(Map.of("tenant", tenant, "payment", payment, "payerName", name, "limit", limit + 1));
        String continuation = "";
        if (after != null) {
            continuation = " WHERE (payer_rank,amount_rank,due_date,id)>(:payerRank,:amountRank,:due,:invoice)";
            parameters.put("payerRank", after.payerRank()); parameters.put("amountRank", after.amountRank());
            parameters.put("due", after.dueDate()); parameters.put("invoice", after.invoiceId());
        }
        String sql = """
                WITH active_lines AS (
                    SELECT o.payment_id,l.invoice_id,l.cash_jpy,l.fee_jpy FROM allocation_operation o
                    JOIN allocation_line l ON l.tenant_id=o.tenant_id AND l.operation_id=o.id
                    WHERE o.tenant_id=:tenant AND o.kind='CONFIRM'
                    AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
                ), payment_position AS (
                    SELECT p.*,COALESCE(s.version,0) AS payment_version,COALESCE(a.cash,0) AS payment_cash
                    FROM payment p LEFT JOIN reconciliation_payment_state s ON s.tenant_id=p.tenant_id AND s.payment_id=p.id
                    LEFT JOIN (SELECT payment_id,sum(cash_jpy) AS cash FROM active_lines GROUP BY payment_id) a ON a.payment_id=p.id
                    WHERE p.tenant_id=:tenant AND p.id=:payment
                ), matched_customers AS (
                    SELECT DISTINCT customer_id FROM payer_alias WHERE tenant_id=:tenant AND active
                    AND payer_name COLLATE "C"=CAST(:payerName AS text) COLLATE "C"
                ), invoice_positions AS (
                    SELECT i.*,c.code,c.name,COALESCE(a.cash,0) AS invoice_cash,COALESCE(a.fee,0) AS invoice_fee,
                        i.amount_jpy::numeric-COALESCE(a.cash,0)-COALESCE(a.fee,0) AS outstanding,
                        CASE WHEN EXISTS(SELECT 1 FROM matched_customers m WHERE m.customer_id=i.customer_id) THEN 0 ELSE 1 END AS payer_rank
                    FROM invoice i JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
                    LEFT JOIN (SELECT invoice_id,sum(cash_jpy) AS cash,sum(fee_jpy) AS fee FROM active_lines GROUP BY invoice_id) a ON a.invoice_id=i.id
                    WHERE i.tenant_id=:tenant AND i.status='ACTIVE'
                ), ranked AS (
                    SELECT i.*,CASE WHEN i.outstanding=p.amount_jpy::numeric-p.payment_cash THEN 0 ELSE 1 END AS amount_rank
                    FROM invoice_positions i CROSS JOIN payment_position p
                    WHERE i.outstanding>0 AND p.amount_jpy::numeric-p.payment_cash>0
                ), page AS (SELECT * FROM ranked
                """ + continuation + " ORDER BY payer_rank,amount_rank,due_date,id LIMIT :limit)" + """
                SELECT p.id AS payment_id,p.transfer_date,p.amount_jpy AS payment_amount,p.payment_cash,p.payment_version,
                    (SELECT count(*) FROM matched_customers) AS matched_count,
                    c.id AS invoice_id,c.customer_id,c.code,c.name,c.invoice_number,c.issue_date,c.due_date,
                    c.amount_jpy,c.version,c.invoice_cash,c.invoice_fee,c.payer_rank,c.amount_rank
                FROM payment_position p LEFT JOIN page c ON true ORDER BY c.payer_rank,c.amount_rank,c.due_date,c.id
                """;
        var rows = jdbc.sql(sql).params(parameters).query((result, index) -> {
            var balance = new LedgerBalances.Payment(BigInteger.valueOf(result.getLong("payment_amount")), result.getBigDecimal("payment_cash").toBigIntegerExact());
            var position = new Payment(result.getObject("payment_id", UUID.class), name, result.getObject("transfer_date", LocalDate.class),
                    balance.unallocated().longValueExact(), result.getLong("payment_version"));
            UUID invoice = result.getObject("invoice_id", UUID.class);
            Candidate candidate = null;
            if (invoice != null) {
                var invoiceBalance = new LedgerBalances.Invoice(BigInteger.valueOf(result.getLong("amount_jpy")),
                        result.getBigDecimal("invoice_cash").toBigIntegerExact(), result.getBigDecimal("invoice_fee").toBigIntegerExact());
                candidate = new Candidate(invoice, result.getObject("customer_id", UUID.class), result.getString("code"), result.getString("name"),
                        result.getString("invoice_number"), result.getObject("issue_date", LocalDate.class), result.getObject("due_date", LocalDate.class),
                        invoiceBalance.amount().longValueExact(), invoiceBalance.outstanding().longValueExact(), result.getLong("version"),
                        result.getInt("payer_rank") == 0, result.getInt("amount_rank") == 0);
            }
            return new Snapshot(position, result.getInt("matched_count"), candidate);
        }).list();
        if (rows.isEmpty()) { throw new Missing(); }
        var candidates = rows.stream().map(Snapshot::candidate).filter(java.util.Objects::nonNull).toList();
        var items = candidates.stream().limit(limit).toList();
        String next = null;
        if (candidates.size() > limit) {
            Candidate last = items.getLast();
            next = new CandidateCursor(payment, last.manualPayerMatch() ? 0 : 1, last.exactAmountMatch() ? 0 : 1,
                    last.dueDate(), last.invoiceId()).encode();
        }
        return new Page(rows.getFirst().payment(), rows.getFirst().matchedCustomers(), items, next);
    }
}
