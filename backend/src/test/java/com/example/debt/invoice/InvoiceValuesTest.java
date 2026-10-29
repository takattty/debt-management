package com.example.debt.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InvoiceValuesTest {
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111113");
    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 29);

    @Test
    void permitsSameDayDueDateAndExactSafeIntegerBoundaries() {
        assertThat(invoice(" INV-001 ", 1).invoiceNumber()).isEqualTo("INV-001");
        InvoiceValues maximum = invoice("INV-002", InvoiceValues.MAX_AMOUNT_JPY);
        assertThat(maximum.amountJpy()).isEqualTo(9_007_199_254_740_991L);
        assertThat(maximum.issueDate()).isEqualTo(maximum.dueDate());
    }

    @Test
    void preservesCaseAndUnicodeFormAndCountsSupplementaryCodePoints() {
        assertThat(invoice("𠮷".repeat(100), 1).invoiceNumber()).isEqualTo("𠮷".repeat(100));
        assertThat(invoice("Inv-ｅ\u0301", 1).invoiceNumber()).isEqualTo("Inv-ｅ\u0301");
        assertFieldFailure(() -> invoice("𠮷".repeat(101), 1), "invoice_number", "NUMBER_TOO_LONG");
    }

    @Test
    void rejectsBlankControlsAndMalformedUnicodeWithoutEchoingInput() {
        Stream.of(null, "", "  ").forEach(value ->
                assertFieldFailure(() -> invoice(value, 1), "invoice_number", "NUMBER_REQUIRED"));
        Stream.of("INV\n001", "\tINV", "INV\r", "INV\u0000", "INV\u2028", "INV\u2029", "INV\ud800", "INV\udc00")
                .forEach(value -> assertFieldFailure(() -> invoice(value, 1), "invoice_number", "INVALID_NUMBER"));
        assertThatThrownBy(() -> invoice("private\ninput", 1)).hasMessageNotContaining("private");
    }

    @Test
    void rejectsMissingCustomerEarlierDueDateAndUnrepresentableTypedDates() {
        assertFieldFailure(() -> new InvoiceValues(null, "INV", ISSUE_DATE, ISSUE_DATE, 1),
                "customer_code", "CUSTOMER_REQUIRED");
        assertFieldFailure(() -> new InvoiceValues(CUSTOMER, "INV", ISSUE_DATE, ISSUE_DATE.minusDays(1), 1),
                "due_date", "DUE_BEFORE_ISSUE");
        assertFieldFailure(() -> new InvoiceValues(CUSTOMER, "INV", null, ISSUE_DATE, 1),
                "issue_date", "INVALID_DATE");
        assertFieldFailure(() -> new InvoiceValues(CUSTOMER, "INV", ISSUE_DATE, null, 1),
                "due_date", "INVALID_DATE");
        assertFieldFailure(() -> new InvoiceValues(CUSTOMER, "INV", LocalDate.of(0, 1, 1), ISSUE_DATE, 1),
                "issue_date", "INVALID_DATE");
        assertFieldFailure(() -> new InvoiceValues(CUSTOMER, "INV", ISSUE_DATE, LocalDate.of(10000, 1, 1), 1),
                "due_date", "INVALID_DATE");
    }

    @Test
    void parsesRealLeapDatesAndRejectsCalendarAndShapeErrors() {
        assertThat(InvoiceValues.parseDate("2028-02-29", "issue_date")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(InvoiceValues.parseDate("0001-01-01", "issue_date")).isEqualTo(LocalDate.of(1, 1, 1));
        assertThat(InvoiceValues.parseDate("9999-12-31", "due_date")).isEqualTo(LocalDate.of(9999, 12, 31));
        Stream.of(null, "", "2026-02-29", "2026-04-31", "2026-00-01", "2026-13-01", "2026-01-00",
                "2026-1-01", "26-01-01", "2026/01/01", "2026-01-01T00:00:00", " 2026-01-01", "2026-01-01 ",
                "+2026-01-01", "+10000-01-01", "0000-01-01", "２０２６-01-01")
                .forEach(value -> assertFieldFailure(() -> InvoiceValues.parseDate(value, "due_date"),
                        "due_date", "INVALID_DATE"));
    }

    @Test
    void parsesOnlyPositiveAsciiYenIntegersWithoutRoundingOrOverflow() {
        assertThat(InvoiceValues.parseAmount("1")).isEqualTo(1);
        assertThat(InvoiceValues.parseAmount("000001")).isEqualTo(1);
        assertThat(InvoiceValues.parseAmount("0".repeat(2047) + "1")).isEqualTo(1);
        assertThat(InvoiceValues.parseAmount("9007199254740991")).isEqualTo(InvoiceValues.MAX_AMOUNT_JPY);
        Stream.of(null, "", "0", "0000", "-1", "+1", "1.0", "1e3", "1,000", "￥100", " 1", "1 ", "１", "١",
                "9007199254740992", "9223372036854775808", "9".repeat(2048))
                .forEach(value -> assertFieldFailure(() -> InvoiceValues.parseAmount(value), "amount", "INVALID_AMOUNT"));
        Stream.of(0L, -1L, InvoiceValues.MAX_AMOUNT_JPY + 1, Long.MAX_VALUE, Long.MIN_VALUE)
                .forEach(value -> assertFieldFailure(() -> invoice("INV", value), "amount", "INVALID_AMOUNT"));
    }

    private static InvoiceValues invoice(String number, long amount) {
        return new InvoiceValues(CUSTOMER, number, ISSUE_DATE, ISSUE_DATE, amount);
    }

    private static void assertFieldFailure(Runnable action, String field, String reasonCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvoiceValidationException.class, failure -> {
            assertThat(failure.field()).isEqualTo(field);
            assertThat(failure.reasonCode()).isEqualTo(reasonCode);
        });
    }
}
