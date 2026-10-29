package com.example.debt.customer;

public record CustomerValues(String code, String name) {
    public CustomerValues {
        code = validate(code, 50, "code");
        name = validate(name, 200, "name");
    }

    private static String validate(String value, int limit, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (normalized.length() > limit || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is too long or contains control characters");
        }
        return normalized;
    }
}
