package com.example.debt.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateCursorTest {
    private final UUID payment = UUID.randomUUID();
    private final UUID invoice = UUID.randomUUID();

    @Test
    void preservesEveryRankAndSupportedDateWithoutLosingThePaymentBinding() {
        for (int payer = 0; payer < 2; payer++) {
            for (int amount = 0; amount < 2; amount++) {
                for (String date : List.of("0001-01-01", "1500-03-01", "1582-10-05", "9999-12-31")) {
                    var cursor = new CandidateCursor(payment, payer, amount, LocalDate.parse(date), invoice);
                    assertThat(cursor.encode()).hasSize(91);
                    assertThat(CandidateCursor.parse(cursor.encode(), payment)).isEqualTo(cursor);
                }
            }
        }
        assertThat(CandidateCursor.parse(null, payment)).isNull();
    }

    @Test
    void rejectsAnotherPaymentMalformedTypesRanksDatesAndNoncanonicalIds() {
        String valid = new CandidateCursor(payment, 0, 1, LocalDate.of(2026, 10, 30), invoice).encode();
        for (String invalid : List.of("", "v2" + valid.substring(2), valid.replace(":0:1:", ":2:1:"),
                valid.replace(":0:1:", ":true:1:"), valid.replace(":0:1:", ":-1:1:"),
                valid.replace("2026-10-30", "2026-02-30"), valid.replace("2026-10-30", "0000-01-01"),
                valid.replace(invoice.toString(), "0-0-0-0-1"), valid + ":extra")) {
            assertThatThrownBy(() -> CandidateCursor.parse(invalid, payment)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CandidateCursor.parse(valid, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
