package com.example.debt.invoice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceState(UUID id, UUID customerId, String customerCode, String customerName, String invoiceNumber,
                           LocalDate issueDate, LocalDate dueDate, long amount, long version, String status, UUID sourceBatchId,
                           Instant createdAt, Instant updatedAt, Instant cancelledAt) {
    public record Facts(UUID customerId, String customerCode, String customerName, String invoiceNumber,
                        LocalDate issueDate, LocalDate dueDate, long amount, String status, UUID sourceBatchId, long version) { }
    public Facts facts() {
        return new Facts(customerId, customerCode, customerName, invoiceNumber, issueDate, dueDate, amount, status, sourceBatchId, version);
    }
}
