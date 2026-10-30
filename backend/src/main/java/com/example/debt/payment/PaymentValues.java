package com.example.debt.payment;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Immutable incoming JPY receipt facts. Customer assignment belongs to settlement decisions. */
public record PaymentValues(String transactionId, LocalDate transferDate, String payerName, long amountJpy) {
    public static final long MAX_AMOUNT_JPY = 9_007_199_254_740_991L;

    public PaymentValues {
        transactionId = text(transactionId, 100);
        payerName = text(payerName, 200);
        if (transferDate == null || transferDate.getYear() < 1 || transferDate.getYear() > 9999) {
            throw new IllegalArgumentException("Invalid transfer date");
        }
        if (amountJpy < 1 || amountJpy > MAX_AMOUNT_JPY) { throw new IllegalArgumentException("Invalid payment amount"); }
    }

    public static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || point >= 0xd800 && point <= 0xdfff || Character.getType(point) == Character.LINE_SEPARATOR
                || Character.getType(point) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException("Invalid payment text");
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) > maximum) { throw new IllegalArgumentException("Payment text too long"); }
        return stripped;
    }

    public static LocalDate date(String value) {
        if (value == null || !value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("Expected YYYY-MM-DD");
        }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid calendar date", invalid); }
    }

    public static long amount(String value) {
        if (value == null || !value.matches("[0-9]+")) { throw new IllegalArgumentException("Expected positive integer JPY"); }
        String digits = value.replaceFirst("^0+", "");
        if (digits.isEmpty() || digits.length() > 16) { throw new IllegalArgumentException("Invalid payment amount"); }
        long amount = Long.parseLong(digits);
        if (amount > MAX_AMOUNT_JPY) { throw new IllegalArgumentException("Invalid payment amount"); }
        return amount;
    }
}
