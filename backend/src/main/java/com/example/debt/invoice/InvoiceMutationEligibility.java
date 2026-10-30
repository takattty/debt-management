package com.example.debt.invoice;

import java.util.UUID;

/** Settlement must inspect its real active cash/fee ledger while the caller holds the invoice row lock. */
public interface InvoiceMutationEligibility {
    void requireUnallocated(UUID tenantId, UUID invoiceId);
}
