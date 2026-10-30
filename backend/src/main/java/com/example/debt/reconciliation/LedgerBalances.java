package com.example.debt.reconciliation;

import java.math.BigInteger;
import java.util.UUID;

/** Exact internal JPY conservation. Public command policies are deliberately absent. */
public final class LedgerBalances {
    private LedgerBalances() { }

    public record Payment(BigInteger amount, BigInteger cash) {
        public Payment {
            requirePositive(amount); requireNonnegative(cash);
            if (cash.compareTo(amount) > 0) { throw new IllegalArgumentException("Payment cash exceeds amount"); }
        }
        public BigInteger unallocated() { return amount.subtract(cash); }
    }

    public record Invoice(BigInteger amount, BigInteger cash, BigInteger fee) {
        public Invoice {
            requirePositive(amount); requireNonnegative(cash); requireNonnegative(fee);
            if (cash.add(fee).compareTo(amount) > 0) { throw new IllegalArgumentException("Invoice allocations exceed amount"); }
        }
        public BigInteger outstanding() { return amount.subtract(cash).subtract(fee); }
    }

    public record Line(UUID invoiceId, long invoiceVersion, BigInteger cash, BigInteger fee) {
        public Line {
            if (invoiceId == null || invoiceVersion < 0) { throw new IllegalArgumentException("Invoice position is required"); }
            requireNonnegative(cash); requireNonnegative(fee);
            if (cash.add(fee).signum() == 0) { throw new IllegalArgumentException("An allocation line must have a cash or fee amount"); }
        }
    }

    private static void requirePositive(BigInteger value) {
        if (value == null || value.signum() <= 0) { throw new IllegalArgumentException("Amount must be positive"); }
    }
    private static void requireNonnegative(BigInteger value) {
        if (value == null || value.signum() < 0) { throw new IllegalArgumentException("Amount must be nonnegative"); }
    }
}
