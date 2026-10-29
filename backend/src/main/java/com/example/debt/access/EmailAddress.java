package com.example.debt.access;

import java.util.Locale;
import java.util.regex.Pattern;

public record EmailAddress(String canonical) {
    private static final Pattern ADDRESS = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    public EmailAddress {
        if (canonical == null) {
            throw new IllegalArgumentException("Email is required");
        }
        canonical = canonical.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > 254 || canonical.indexOf('@') > 64 || !ADDRESS.matcher(canonical).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }
}
