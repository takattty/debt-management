package com.example.debt.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Single-process limits; reservations retain concurrent work even when a window expires or a login succeeds. */
@Component
public class LoginAttemptLimiter {
    private static final int EMAIL_LIMIT = 10;
    private static final int IP_LIMIT = 60;
    private final Clock clock;
    private final int capacity;
    private final Map<String, Bucket> buckets = new HashMap<>();

    @Autowired
    public LoginAttemptLimiter(@Qualifier("loginRateLimitClock") Clock clock) {
        this(clock, 10000);
    }

    LoginAttemptLimiter(Clock clock, int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Rate limit capacity must allow both keys");
        }
        this.clock = clock;
        this.capacity = capacity;
    }

    public synchronized Attempt reserve(EmailAddress email, String remoteAddress) {
        Instant now = clock.instant();
        buckets.values().removeIf(bucket -> bucket.active == 0 && !now.isBefore(bucket.until));
        String emailKey = key("email:", email.canonical());
        String ipKey = key("ip:", remoteAddress == null ? "unknown" : remoteAddress);
        Bucket byEmail = buckets.get(emailKey);
        Bucket byIp = buckets.get(ipKey);
        if (byEmail != null) { byEmail.refresh(now); }
        if (byIp != null) {
            byIp.refresh(now);
            if (byIp.attempts >= IP_LIMIT || byIp.active >= IP_LIMIT) { throw limited(now, byIp.until); }
        }
        int needed = (byEmail == null ? 1 : 0) + (byIp == null ? 1 : 0);
        if (buckets.size() + needed > capacity) {
            Instant earliest = buckets.values().stream().map(bucket -> bucket.until).min(Instant::compareTo).orElse(now);
            throw limited(now, earliest);
        }
        if (byIp == null) { byIp = new Bucket(now); buckets.put(ipKey, byIp); }
        // Validated attempts denied by the email budget still count against the remote address.
        byIp.attempts++;
        if (byEmail == null) { byEmail = new Bucket(now); buckets.put(emailKey, byEmail); }
        if (byEmail.attempts + byEmail.active >= EMAIL_LIMIT) { throw limited(now, byEmail.until); }
        byEmail.active++;
        byIp.active++;
        return new Attempt(emailKey, byEmail, byIp, byEmail.generation);
    }

    private synchronized void complete(Attempt attempt, boolean success) {
        if (attempt.completed) { return; }
        attempt.completed = true;
        Instant now = clock.instant();
        Bucket email = attempt.email;
        email.refresh(now);
        email.active--;
        attempt.ip.active--;
        if (success) {
            email.attempts = 0;
            email.until = now.plusSeconds(60);
        } else if (attempt.generation == email.generation) {
            email.attempts++;
        }
        if (email.active == 0 && email.attempts == 0) { buckets.remove(attempt.emailKey, email); }
    }

    private LoginRateLimited limited(Instant now, Instant until) {
        long millis = java.time.Duration.between(now, until).toMillis();
        return new LoginRateLimited(Math.max(1, Math.min(60, (millis + 999) / 1000)));
    }

    private static String key(String prefix, String value) {
        try {
            return prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static final class Bucket {
        private Instant until;
        private int attempts;
        private int active;
        private long generation;
        Bucket(Instant now) { until = now.plusSeconds(60); }
        void refresh(Instant now) {
            if (!now.isBefore(until)) { attempts = 0; generation++; until = now.plusSeconds(60); }
        }
    }

    public final class Attempt implements AutoCloseable {
        private final String emailKey;
        private final Bucket email;
        private final Bucket ip;
        private final long generation;
        private boolean completed;
        private Attempt(String key, Bucket email, Bucket ip, long generation) {
            this.emailKey = key; this.email = email; this.ip = ip; this.generation = generation;
        }
        public void success() { complete(this, true); }
        @Override public void close() { complete(this, false); }
        @Override public String toString() { return "LoginAttempt[REDACTED]"; }
    }
}
