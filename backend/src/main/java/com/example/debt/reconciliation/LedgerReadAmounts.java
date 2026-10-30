package com.example.debt.reconciliation;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Embed in the owning fact query so amounts, versions and balances share one SQL snapshot. */
public final class LedgerReadAmounts {
    private LedgerReadAmounts() { }

    public static final String PAYMENT_JOIN = """
            LEFT JOIN LATERAL (
                SELECT sum(l.cash_jpy) cash FROM allocation_operation o
                JOIN allocation_line l ON l.tenant_id=o.tenant_id AND l.operation_id=o.id
                WHERE o.tenant_id=p.tenant_id AND o.payment_id=p.id AND o.kind='CONFIRM'
                AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
            ) a ON true
            """;
    public static final String INVOICE_JOIN = """
            LEFT JOIN LATERAL (
                SELECT sum(l.cash_jpy) cash,sum(l.fee_jpy) fee FROM allocation_line l
                JOIN allocation_operation o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
                WHERE l.tenant_id=i.tenant_id AND l.invoice_id=i.id AND o.kind='CONFIRM'
                AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
            ) a ON true
            """;

    public static long unallocated(ResultSet result) throws SQLException {
        return new LedgerBalances.Payment(BigInteger.valueOf(result.getLong("amount_jpy")), integer(result, "cash"))
                .unallocated().longValueExact();
    }

    public static long outstanding(ResultSet result) throws SQLException {
        var balance = new LedgerBalances.Invoice(BigInteger.valueOf(result.getLong("amount_jpy")), integer(result, "cash"), integer(result, "fee"));
        return result.getString("status").equals("CANCELLED") ? 0 : balance.outstanding().longValueExact();
    }

    private static BigInteger integer(ResultSet result, String column) throws SQLException {
        var value = result.getBigDecimal(column);
        return value == null ? BigInteger.ZERO : value.toBigIntegerExact();
    }
}
