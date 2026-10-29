package com.example.debt.access;

import java.util.Locale;
import java.util.regex.Pattern;

public record EmailAddress(String canonical) {
    private static final Pattern ADDRESS = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}");

    public EmailAddress {
        if (canonical == null) {
            throw new IllegalArgumentException("Email is required");
        }
        canonical = canonical.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > 254 || !ADDRESS.matcher(canonical).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }
}
