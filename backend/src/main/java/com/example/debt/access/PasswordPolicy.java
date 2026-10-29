package com.example.debt.access;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validateLogin(String password) {
        if (password == null || password.isEmpty() || password.codePointCount(0, password.length()) > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }

    public static void validateCreation(String password) {
        validateLogin(password);
        if (password.codePointCount(0, password.length()) < 12) {
            throw new IllegalArgumentException("Password requires at least 12 code points");
        }
    }
}
