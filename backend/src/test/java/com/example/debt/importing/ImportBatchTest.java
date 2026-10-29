package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportBatchTest {
    private static final Instant CREATED = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(86400);
    private final UUID actor = UUID.randomUUID();

    @Test
    void expiryIncludesTheExactBoundaryButConfirmedMetadataDoesNotExpire() {
        ImportBatch preview = batch(ImportBatch.Status.PREVIEW_VALID, 0, null, null, EXPIRES, "a".repeat(64));
        assertThat(preview.isPreviewExpired(clock(EXPIRES.minusNanos(1)))).isFalse();
        assertThat(preview.isPreviewExpired(clock(EXPIRES))).isTrue();
        assertThat(preview.isPreviewExpired(clock(EXPIRES.plusSeconds(1)))).isTrue();
        assertThat(batch(ImportBatch.Status.CONFIRMED, 0, actor, CREATED, EXPIRES, "a".repeat(64))
                .isPreviewExpired(clock(EXPIRES))).isFalse();
    }

    @Test
    void rejectsInconsistentStatusExpiryAndPartialConfirmationMetadata() {
        assertThat(batch(ImportBatch.Status.PREVIEW_INVALID, 1, null, null, EXPIRES, "a".repeat(64)).errorCount()).isEqualTo(1);
        assertThatThrownBy(() -> batch(ImportBatch.Status.PREVIEW_INVALID, 0, null, null, EXPIRES, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch(ImportBatch.Status.PREVIEW_VALID, 1, null, null, EXPIRES, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch(ImportBatch.Status.PREVIEW_VALID, 0, actor, null, EXPIRES, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch(ImportBatch.Status.CONFIRMED, 0, actor, EXPIRES, EXPIRES, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch(ImportBatch.Status.PREVIEW_VALID, 0, null, null, CREATED, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batch(ImportBatch.Status.PREVIEW_VALID, 0, null, null, EXPIRES, "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ImportBatch batch(ImportBatch.Status status, int errors, UUID confirmedBy, Instant confirmedAt,
            Instant expires, String fingerprint) {
        return new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), ImportBatch.Kind.CUSTOMER, 1, fingerprint,
                status, status == ImportBatch.Status.CONFIRMED ? 1 : 0, actor, CREATED, expires,
                confirmedBy, confirmedAt, 1, errors);
    }

    private Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
