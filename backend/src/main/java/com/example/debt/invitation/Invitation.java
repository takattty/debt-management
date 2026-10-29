package com.example.debt.invitation;

import com.example.debt.access.UserRole;
import java.time.Instant;
import java.util.UUID;

/** Persisted invitation snapshot; the bearer token is never part of this object. */
public record Invitation(UUID id, UUID tenantId, String email, UserRole role, String tokenHash,
        UUID createdByUserId, Instant createdAt, Instant expiresAt, Instant acceptedAt, UUID acceptedUserId) {
    public boolean isAvailableAt(Instant now) {
        return acceptedAt == null && now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "Invitation[id=" + id + ", tenantId=" + tenantId + ", role=" + role + ", expiresAt=" + expiresAt + "]";
    }
}
