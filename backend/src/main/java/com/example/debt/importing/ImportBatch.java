package com.example.debt.importing;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Passive metadata only: no confirmation operation, persistence, or business-row validation. */
public record ImportBatch(UUID id, UUID tenantId, Kind kind, int formatVersion, String semanticFingerprint,
                          Status status, long version, UUID createdByUserId, Instant createdAt, Instant expiresAt,
                          UUID confirmedByUserId, Instant confirmedAt, int rowCount, int errorCount) {
    public enum Kind { CUSTOMER, INVOICE, PAYMENT }
    public enum Status { PREVIEW_VALID, PREVIEW_INVALID, CONFIRMED }

    public ImportBatch {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdByUserId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        if (semanticFingerprint == null || !semanticFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fingerprint must be a lowercase SHA-256 hex value");
        }
        if (formatVersion <= 0 || version < 0 || rowCount < 0 || rowCount > 10_000 || errorCount < 0) {
            throw new IllegalArgumentException("Invalid import counters or version");
        }
        if (!expiresAt.isAfter(createdAt) || (status == Status.PREVIEW_INVALID) != (errorCount > 0)) {
            throw new IllegalArgumentException("Invalid preview expiry or error state");
        }
        if (status == Status.CONFIRMED) {
            if (confirmedByUserId == null || confirmedAt == null || version == 0
                    || confirmedAt.isBefore(createdAt) || !confirmedAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException("Invalid confirmation metadata");
            }
        } else if (confirmedByUserId != null || confirmedAt != null) {
            throw new IllegalArgumentException("Preview cannot contain confirmation metadata");
        }
    }

    public boolean isPreviewExpired(Clock clock) {
        return status != Status.CONFIRMED && !clock.instant().isBefore(expiresAt);
    }
}
