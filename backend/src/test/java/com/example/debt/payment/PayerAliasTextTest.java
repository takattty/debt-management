package com.example.debt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.debt.importing.PaymentCsv;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PayerAliasTextTest {
    @Test
    void reusesCsvPayerRuleWithoutUnicodeWidthCaseOrKanaNormalization() {
        var names = List.of("　ｶｸｳ　", "カクウ", "KAKUU", "kakuu", "é", "e\u0301");
        var normalized = names.stream().map(name -> PaymentValues.text(name, 200)).toList();
        assertThat(normalized).containsExactly("ｶｸｳ", "カクウ", "KAKUU", "kakuu", "é", "e\u0301").doesNotHaveDuplicates();
        for (String name : names) {
            var csv = new PaymentCsv().parse(new ByteArrayInputStream((PaymentCsv.HEADER + "ID,2026-10-30," + name + ",1\n")
                    .getBytes(StandardCharsets.UTF_8)), ignored -> Set.of());
            assertThat(csv.errors()).isEmpty();
            assertThat(csv.rows().getFirst().payerName()).isEqualTo(PaymentValues.text(name, 200));
        }
        assertThat(PaymentValues.text("　" + "😀".repeat(200) + "　", 200)).isEqualTo("😀".repeat(200));
    }

    @Test
    void rejectsControlsBeforeStrippingAndCountsCodePoints() {
        for (String name : List.of("", "　 ", "name\n", "\tname", "a\u2028b", "a\u2029b", "\ud800", "😀".repeat(201))) {
            assertThatThrownBy(() -> PaymentValues.text(name, 200)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
