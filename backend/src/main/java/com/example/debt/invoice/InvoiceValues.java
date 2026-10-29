package com.example.debt.invoice;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Invoice facts shared by registration and correction; balances are derived separately.
 * Invoice numbers strip surrounding whitespace without case folding or Unicode normalization.
 * Dates use years 0001 through 9999 and amounts remain exact in both Java and JavaScript.
 */
public record InvoiceValues(UUID customerId, String invoiceNumber, LocalDate issueDate, LocalDate dueDate,
                            long amountJpy) {
    public static final long MAX_AMOUNT_JPY = 9_007_199_254_740_991L;
    public static final int MAX_NUMBER_CODE_POINTS = 100;

    public InvoiceValues {
        if (customerId == null) {
            throw invalid("customer_code", "CUSTOMER_REQUIRED", "Customer is required");
        }
        invoiceNumber = validateNumber(invoiceNumber);
        validateDate(issueDate, "issue_date");
        validateDate(dueDate, "due_date");
        if (dueDate.isBefore(issueDate)) {
            throw invalid("due_date", "DUE_BEFORE_ISSUE", "Due date must be on or after issue date");
        }
        validateAmount(amountJpy);
    }

    /** Fixed CSV dates are exactly YYYY-MM-DD in the common Java/PostgreSQL year range. */
    public static LocalDate parseDate(String value, String field) {
        if (value == null || !value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw invalid(field, "INVALID_DATE", "Date must be YYYY-MM-DD with year 0001 through 9999");
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException failure) {
            throw invalid(field, "INVALID_DATE", "Date must be a valid calendar date");
        }
    }

    /** Parse without a floating point, locale-sensitive, or exponent conversion. */
    public static long parseAmount(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw invalid("amount", "INVALID_AMOUNT", "Amount must be a positive integer in yen");
        }
        String significantDigits = value.replaceFirst("^0+", "");
        if (significantDigits.isEmpty() || significantDigits.length() > 16) {
            throw invalid("amount", "INVALID_AMOUNT", "Amount must be between 1 and 9007199254740991 yen");
        }
        long amount = Long.parseLong(significantDigits);
        validateAmount(amount);
        return amount;
    }

    private static String validateNumber(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("invoice_number", "NUMBER_REQUIRED", "Invoice number is required");
        }
        if (value.codePoints().anyMatch(InvoiceValues::isInvalidNumberCodePoint)) {
            throw invalid("invoice_number", "INVALID_NUMBER", "Invoice number contains unsupported characters");
        }
        String number = value.strip();
        if (number.codePointCount(0, number.length()) > MAX_NUMBER_CODE_POINTS) {
            throw invalid("invoice_number", "NUMBER_TOO_LONG", "Invoice number exceeds 100 code points");
        }
        return number;
    }

    private static boolean isInvalidNumberCodePoint(int point) {
        return Character.isISOControl(point) || point >= 0xd800 && point <= 0xdfff
                || Character.getType(point) == Character.LINE_SEPARATOR
                || Character.getType(point) == Character.PARAGRAPH_SEPARATOR;
    }

    private static void validateDate(LocalDate value, String field) {
        if (value == null || value.getYear() < 1 || value.getYear() > 9999) {
            throw invalid(field, "INVALID_DATE", "Date must have year 0001 through 9999");
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT_JPY) {
            throw invalid("amount", "INVALID_AMOUNT", "Amount must be between 1 and 9007199254740991 yen");
        }
    }

    private static InvoiceValidationException invalid(String field, String code, String message) {
        return new InvoiceValidationException(field, code, message);
    }
}
