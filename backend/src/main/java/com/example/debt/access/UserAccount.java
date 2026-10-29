package com.example.debt.access;

import java.util.UUID;

public record UserAccount(UUID id, UUID tenantId, String email, String passwordHash, UserRole role, boolean enabled) {
    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", tenantId=" + tenantId + ", role=" + role + ", enabled=" + enabled + "]";
    }
}
