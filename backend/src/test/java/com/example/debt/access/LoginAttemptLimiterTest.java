package com.example.debt.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limits = new LoginAttemptLimiter(clock);

    @Test
    void tenFailuresBlockCanonicalEmailUntilExactWindowBoundary() {
        for (int i = 0; i < 10; i++) { reserve(limits, i % 2 == 0 ? " User@EXAMPLE.TEST " : "user@example.test", "ip").close(); }
        assertThatThrownBy(() -> reserve(limits, "user@example.test", "different-ip")).isInstanceOf(LoginRateLimited.class);
        clock.now = clock.now.plusSeconds(59);
        try { reserve(limits, "user@example.test", "ip"); throw new AssertionError("Expected a limit"); }
        catch (LoginRateLimited denied) { assertThat(denied.retryAfterSeconds()).isEqualTo(1); }
        clock.now = clock.now.plusSeconds(1);
        reserve(limits, "user@example.test", "ip").close();
    }

    @Test
    void successfulLoginResetsFailuresButDoesNotEraseOtherInFlightReservations() {
        List<LoginAttemptLimiter.Attempt> active = new ArrayList<>();
        for (int i = 0; i < 10; i++) { active.add(reserve(limits, "user@example.test", "ip")); }
        active.removeFirst().success();
        active.add(reserve(limits, "user@example.test", "ip"));
        assertThatThrownBy(() -> reserve(limits, "user@example.test", "ip")).isInstanceOf(LoginRateLimited.class);
        active.forEach(LoginAttemptLimiter.Attempt::close);
        assertThatThrownBy(() -> reserve(limits, "user@example.test", "ip")).isInstanceOf(LoginRateLimited.class);
        clock.now = clock.now.plusSeconds(60);
        for (int i = 0; i < 9; i++) { reserve(limits, "user@example.test", "ip").close(); }
        reserve(limits, "user@example.test", "ip").success();
        reserve(limits, "user@example.test", "ip").close();
    }

    @Test
    void successfulLoginsStillConsumeIpBudgetAndRecoverAtDeadline() {
        for (int i = 0; i < 60; i++) { reserve(limits, "user" + i + "@example.test", "same-ip").success(); }
        assertThatThrownBy(() -> reserve(limits, "another@example.test", "same-ip")).isInstanceOf(LoginRateLimited.class);
        reserve(limits, "another@example.test", "other-ip").success();
        clock.now = clock.now.plusSeconds(60);
        reserve(limits, "another@example.test", "same-ip").success();
    }

    @Test
    void boundedCapacityFailsClosedAndExpiredKeysRecoverWithoutEvictingActiveWork() {
        var bounded = new LoginAttemptLimiter(clock, 2);
        var active = reserve(bounded, "first@example.test", "ip");
        assertThat(active.toString()).doesNotContain("first@example.test", "ip");
        assertThatThrownBy(() -> reserve(bounded, "second@example.test", "ip")).isInstanceOf(LoginRateLimited.class);
        clock.now = clock.now.plusSeconds(60);
        assertThatThrownBy(() -> reserve(bounded, "second@example.test", "ip")).isInstanceOf(LoginRateLimited.class);
        active.close();
        clock.now = clock.now.plusSeconds(60);
        reserve(bounded, "second@example.test", "ip").success();
    }

    @Test
    void simultaneousReservationsAllowOnlyTenWorkersBeforeHashing() throws Exception {
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(16);
        CountDownLatch finish = new CountDownLatch(1);
        var admitted = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        try (var attempt = reserve(limits, "concurrent@example.test", "ip")) {
                            admitted.incrementAndGet(); attempted.countDown(); finish.await();
                        } catch (LoginRateLimited denied) { attempted.countDown(); }
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            try {
                boolean allReady = ready.await(10, TimeUnit.SECONDS); start.countDown();
                assertThat(allReady).isTrue();
                assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(admitted.get()).isEqualTo(10);
            } finally { start.countDown(); finish.countDown(); }
            for (var future : futures) { future.get(10, TimeUnit.SECONDS); }
        }
    }

    private LoginAttemptLimiter.Attempt reserve(LoginAttemptLimiter limiter, String email, String ip) {
        return limiter.reserve(new EmailAddress(email), ip);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-29T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
