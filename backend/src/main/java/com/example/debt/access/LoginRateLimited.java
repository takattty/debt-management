package com.example.debt.access;

public class LoginRateLimited extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginRateLimited(long retryAfterSeconds) {
        super("Login attempts are temporarily limited");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
