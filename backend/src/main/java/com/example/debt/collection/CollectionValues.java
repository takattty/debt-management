package com.example.debt.collection;

import com.example.debt.payment.PaymentValues;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class CollectionValues {
    private CollectionValues() { }
    public static LocalDate date(LocalDate value) {
        if (value == null) { throw new IllegalArgumentException("Business date is required"); }
        return PaymentValues.date(value.toString());
    }
    public static int daysOverdue(LocalDate dueDate, LocalDate asOf) {
        return Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(date(dueDate), date(asOf))));
    }
    public static String note(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 5000
                || value.codePoints().anyMatch(point -> point >= 0xd800 && point <= 0xdfff
                    || Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) {
            throw new IllegalArgumentException("A shared note of 1 through 5000 code points is required");
        }
        return value.strip();
    }
}
