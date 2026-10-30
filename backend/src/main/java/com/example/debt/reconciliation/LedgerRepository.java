package com.example.debt.reconciliation;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal reads and a shared lock primitive; no allocation command or public adapter is enabled. */
@Repository
public class LedgerRepository {
    private final JdbcClient jdbc;
    public record PaymentPosition(UUID id, long version, LedgerBalances.Payment balance) { }
    public record InvoicePosition(UUID id, long version, String status, LedgerBalances.Invoice balance) { }
    public record LockedPositions(PaymentPosition payment, List<InvoicePosition> invoices) { }

    public LedgerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public PaymentPosition payment(UUID tenant, UUID id) {
        return jdbc.sql("""
                SELECT p.id,p.amount_jpy,COALESCE(s.version,0) AS version,COALESCE(a.cash,0) AS cash
                FROM payment p LEFT JOIN reconciliation_payment_state s ON s.tenant_id=p.tenant_id AND s.payment_id=p.id
                LEFT JOIN LATERAL (
                    SELECT sum(l.cash_jpy) AS cash FROM allocation_operation o
                    JOIN allocation_line l ON l.tenant_id=o.tenant_id AND l.operation_id=o.id
                    WHERE o.tenant_id=p.tenant_id AND o.payment_id=p.id AND o.kind='CONFIRM'
                    AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
                ) a ON true WHERE p.tenant_id=:tenant AND p.id=:id
                """).param("tenant", tenant).param("id", id).query((result, row) -> new PaymentPosition(result.getObject("id", UUID.class),
                        result.getLong("version"), new LedgerBalances.Payment(BigInteger.valueOf(result.getLong("amount_jpy")),
                        result.getBigDecimal("cash").toBigIntegerExact()))).optional().orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    public InvoicePosition invoice(UUID tenant, UUID id) {
        return jdbc.sql("""
                SELECT i.id,i.version,i.status,i.amount_jpy,COALESCE(a.cash,0) AS cash,COALESCE(a.fee,0) AS fee FROM invoice i
                LEFT JOIN LATERAL (
                    SELECT sum(l.cash_jpy) AS cash,sum(l.fee_jpy) AS fee FROM allocation_line l
                    JOIN allocation_operation o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
                    WHERE l.tenant_id=i.tenant_id AND l.invoice_id=i.id AND o.kind='CONFIRM'
                    AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
                ) a ON true WHERE i.tenant_id=:tenant AND i.id=:id
                """).param("tenant", tenant).param("id", id).query((result, row) -> new InvoicePosition(result.getObject("id", UUID.class),
                        result.getLong("version"), result.getString("status"), new LedgerBalances.Invoice(BigInteger.valueOf(result.getLong("amount_jpy")),
                        result.getBigDecimal("cash").toBigIntegerExact(), result.getBigDecimal("fee").toBigIntegerExact())))
                .optional().orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedPositions lockPositions(UUID tenant, UUID paymentId, Collection<UUID> invoiceIds) {
        var ids = invoiceIds.stream().distinct().toList();
        jdbc.sql("""
                INSERT INTO reconciliation_payment_state(tenant_id,payment_id)
                SELECT tenant_id,id FROM payment WHERE tenant_id=:tenant AND id=:id ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("id", paymentId).update();
        jdbc.sql("SELECT version FROM reconciliation_payment_state WHERE tenant_id=? AND payment_id=? FOR UPDATE")
                .params(tenant, paymentId).query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        List<UUID> locked = ids.isEmpty() ? List.of() : jdbc.sql("SELECT id FROM invoice WHERE tenant_id=:tenant AND id IN (:ids) ORDER BY id FOR UPDATE")
                .param("tenant", tenant).param("ids", ids).query(UUID.class).list();
        if (locked.size() != ids.size()) { throw new IllegalArgumentException("Invoice not found"); }
        return new LockedPositions(payment(tenant, paymentId), locked.stream().map(id -> invoice(tenant, id)).toList());
    }
}
