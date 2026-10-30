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
}
