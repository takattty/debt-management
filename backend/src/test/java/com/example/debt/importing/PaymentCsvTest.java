package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.debt.payment.PaymentValues;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentCsvTest {
    @Test
    void canonicalAmountsStrictDatesAndWhitespaceOnlyPreservePayerDistinctions() {
        var preview = parse(PaymentCsv.HEADER + " T1 ,2024-02-29, ｶｸｳ ,000100\nT2,9999-12-31,カクウ,9007199254740991\n");
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.rows().getFirst().transactionId()).isEqualTo("T1");
        assertThat(preview.rows().getFirst().payerName()).isEqualTo("ｶｸｳ");
        assertThat(preview.rows().getFirst().amount()).isEqualTo("100");
        for (String value : List.of("0", "-1", "+1", "1.0", "1e2", "1,000", "９", "9007199254740992")) {
            assertThatThrownBy(() -> PaymentValues.amount(value)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String value : List.of("2023-02-29", "2026-04-31", "0000-01-01", "2026-1-01", "2026-01-01 ")) {
            assertThatThrownBy(() -> PaymentValues.date(value)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String value : List.of("\tname", "name\n", "name\0", "name\u2028", " ")) {
            assertThatThrownBy(() -> PaymentValues.text(value, 200)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PaymentValues.text("𠮷".repeat(200), 200)).hasSize(400);
        assertThatThrownBy(() -> PaymentValues.text("𠮷".repeat(201), 200)).isInstanceOf(IllegalArgumentException.class);
        for (String pair : List.of("カクウ|ｶｸｳ", "KAKUU|kakuu", "é|é")) {
            String[] values = pair.split("\\|");
            assertThat(parse(PaymentCsv.HEADER + "T,2026-01-01," + values[0] + ",1\n").fingerprint())
                    .isNotEqualTo(parse(PaymentCsv.HEADER + "T,2026-01-01," + values[1] + ",1\n").fingerprint());
        }
    }

    @Test
    void fingerprintIgnoresBomNewlinesAndOrderButRetainsMultiplicityAndFieldFraming() {
        String a = "A,2026-01-01,架空A,1", b = "B,2026-01-02,架空B,2";
        var plain = parse(PaymentCsv.HEADER + a + "\r\n" + b + "\r\n");
        assertThat(plain.fingerprint()).isEqualTo(parse("\ufeff" + PaymentCsv.HEADER.replace("\r\n", "\n") + b + "\n" + a + "\n").fingerprint());
        assertThat(plain.fingerprint()).isNotEqualTo(parse(PaymentCsv.HEADER + a + "\n" + a + "\n" + b + "\n").fingerprint());
        assertThat(parse(PaymentCsv.HEADER + a + "\n" + a + "\n").errors()).extracting(CsvError::reasonCode)
                .containsExactly("DUPLICATE_TRANSACTION_ID", "DUPLICATE_TRANSACTION_ID");
    }

    @Test
    void errorsRetainPhysicalStartLinesAndSafeVisibleNul() {
        var parsed = parse(PaymentCsv.HEADER + "A,2026-01-01,\"架空\n名義\",1\nB,bad,名義,0\nC,\0,名義,1\n");
        assertThat(parsed.errors()).extracting(CsvError::rowNumber).containsExactly(2, 4, 4, 5);
        assertThat(parsed.rows().getLast().transferDate()).isEqualTo("␀");
        assertThat(parse("bad\n").errors()).extracting(CsvError::reasonCode).containsExactly("INVALID_HEADER");
        assertThat(parse(PaymentCsv.HEADER).errors()).extracting(CsvError::reasonCode).containsExactly("EMPTY_IMPORT");
    }

    private PaymentCsv.Preview parse(String csv) {
        return new PaymentCsv().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), ids -> Set.of());
    }
}
