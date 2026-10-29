package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomerCsvTest {
    @Test
    void fingerprintIgnoresBomNewlineRecordOrderAndQuotingWithoutDroppingDuplicateMultiplicity() {
        var first = parse(CustomerCsv.HEADER + "C1,架空A\r\nC2,架空B\r\n");
        var second = parse("\ufeffcustomer_code,customer_name\n\"C2\",\"架空B\"\nC1,架空A");
        var duplicate = parse(CustomerCsv.HEADER + "C1,架空A\nC2,架空B\nC2,架空B\n");
        assertThat(first.errors()).isEmpty();
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint()).isNotEqualTo(duplicate.fingerprint());
        assertThat(duplicate.errors()).extracting(CsvError::rowNumber).containsExactly(3, 4);
        assertThat(parse(CustomerCsv.HEADER + "\"A,B\",C\n").fingerprint())
                .isNotEqualTo(parse(CustomerCsv.HEADER + "A,\"B,C\"\n").fingerprint());
    }

    @Test
    void rejectsControlCharactersBeforeManualCustomerWhitespaceRulesAndDisplaysNulSafely() {
        var preview = parse(CustomerCsv.HEADER + "\"\nC1\",架空A\nC2,\"架空B\n\"\nC3,架空\0C\n");
        assertThat(preview.errors()).hasSize(3).extracting(CsvError::rowNumber).containsExactly(2, 4, 6);
        assertThat(preview.rows()).noneMatch(CustomerCsv.Row::valid);
        assertThat(preview.errors().getLast().inputValue()).isEqualTo("架空␀C");
        assertThat(preview.rows().getLast().customerName()).isEqualTo("架空␀C");
    }

    @Test
    void normalizesOnlyExplicitManualCustomerWhitespaceAndPreservesUnicodeAndQuotedCommas() {
        var preview = parse(CustomerCsv.HEADER + " C1 ,\" 架空,商事😀 \"\n");
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.rows().getFirst().customerCode()).isEqualTo("C1");
        assertThat(preview.rows().getFirst().customerName()).isEqualTo("架空,商事😀");
    }

    @Test
    void existingCodeAndRepeatedCodesInvalidateEveryOccurrenceAndLookupIsBatched() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var preview = new CustomerCsv().parse(bytes(CustomerCsv.HEADER + " C1 ,A\nC1,B\nC2,C\n"), codes -> {
            calls.incrementAndGet(); assertThat(codes).containsExactlyInAnyOrder("C1", "C2"); return Set.of("C2");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(preview.errors()).extracting(CsvError::reasonCode).containsExactly("DUPLICATE_CODE", "DUPLICATE_CODE", "CUSTOMER_EXISTS");
        assertThat(preview.rows()).noneMatch(CustomerCsv.Row::valid);
    }

    @Test
    void validatesHeaderEmptyImportColumnsAndTruncatesOverlongInputOnCodePointBoundary() {
        assertThat(parse("").errors().getFirst().reasonCode()).isEqualTo("INVALID_HEADER");
        assertThat(parse("customer_name,customer_code\nA,C1\n").rows()).isEmpty();
        assertThat(parse(CustomerCsv.HEADER).errors().getFirst().reasonCode()).isEqualTo("EMPTY_IMPORT");
        assertThat(parse(CustomerCsv.HEADER + "C1,A,extra\n").errors().getFirst().reasonCode()).isEqualTo("COLUMN_COUNT");
        var invalid = parse(CustomerCsv.HEADER + "C1," + "😀".repeat(201) + "\n");
        assertThat(invalid.errors().getFirst().inputValue()).isEqualTo("😀".repeat(200));
        assertThat(invalid.errors().getFirst().inputTruncated()).isTrue();
        assertThat(parse("customer_code,customer_n\0ame\n").errors().getFirst().inputValue()).contains("␀");
    }

    private CustomerCsv.Preview parse(String value) {
        return new CustomerCsv().parse(bytes(value), codes -> Set.of());
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
