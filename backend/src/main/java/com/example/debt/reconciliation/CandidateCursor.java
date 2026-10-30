package com.example.debt.reconciliation;

import com.example.debt.payment.PaymentValues;
import java.time.LocalDate;
import java.util.UUID;

record CandidateCursor(UUID paymentId, int payerRank, int amountRank, LocalDate dueDate, UUID invoiceId) {
    static CandidateCursor parse(String value, UUID payment) {
        if (value == null) { return null; }
        String[] parts = value.split(":", -1);
        if (parts.length != 6 || !parts[0].equals("v1")) { throw new IllegalArgumentException("Invalid candidate cursor"); }
        var cursor = new CandidateCursor(UUID.fromString(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                PaymentValues.date(parts[4]), UUID.fromString(parts[5]));
        if (!cursor.paymentId().equals(payment) || cursor.payerRank() < 0 || cursor.payerRank() > 1
                || cursor.amountRank() < 0 || cursor.amountRank() > 1 || !cursor.encode().equals(value)) {
            throw new IllegalArgumentException("Invalid candidate cursor");
        }
        return cursor;
    }

    String encode() { return "v1:" + paymentId + ":" + payerRank + ":" + amountRank + ":" + dueDate + ":" + invoiceId; }
}
