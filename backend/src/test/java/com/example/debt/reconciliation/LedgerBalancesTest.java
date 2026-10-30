package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerBalancesTest {
    @Test
    void computesExactConservationAndRejectsAmountsThatWouldOverflowFixedWidthAddition() {
        BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE);
        assertThat(new LedgerBalances.Payment(huge.add(BigInteger.TEN), huge).unallocated()).isEqualTo(BigInteger.TEN);
        assertThatThrownBy(() -> new LedgerBalances.Invoice(huge, huge, huge)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerBalances.Payment(BigInteger.TEN, huge)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerBalances.Invoice(BigInteger.TEN, BigInteger.ONE.negate(), BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LedgerBalances.Invoice(BigInteger.TEN, BigInteger.valueOf(6), BigInteger.ONE).outstanding())
                .isEqualTo(BigInteger.valueOf(3));
    }

    @Test
    void representsExplicitFeeWithoutCashWithoutDecidingItsFutureCommandPolicy() {
        var line = new LedgerBalances.Line(UUID.randomUUID(), 0, BigInteger.ZERO, BigInteger.TEN);
        assertThat(line.cash()).isZero();
        assertThat(new LedgerBalances.Invoice(BigInteger.TEN, line.cash(), line.fee()).outstanding()).isZero();
        assertThatThrownBy(() -> new LedgerBalances.Line(UUID.randomUUID(), 0, BigInteger.ZERO, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
