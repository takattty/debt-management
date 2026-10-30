package com.example.debt.reconciliation;

import com.example.debt.invoice.InvoiceConflict;
import com.example.debt.invoice.InvoiceMutationEligibility;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The caller first locks the invoice; this separate statement sees any writer it waited for. */
public class LedgerInvoiceMutationEligibility implements InvoiceMutationEligibility {
    private final LedgerRepository ledger;

    public LedgerInvoiceMutationEligibility(LedgerRepository ledger) { this.ledger = ledger; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUnallocated(UUID tenant, UUID invoice) {
        var balance = ledger.invoice(tenant, invoice).balance();
        if (balance.cash().signum() != 0 || balance.fee().signum() != 0) {
            throw new InvoiceConflict("INVOICE_ALLOCATED");
        }
    }
}
