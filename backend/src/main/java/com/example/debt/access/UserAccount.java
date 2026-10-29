package com.example.debt.access;

import java.util.UUID;

public record UserAccount(UUID id, UUID tenantId, String email, String passwordHash, UserRole role, boolean enabled) {
    public TenantUser principal() {
        return new TenantUser(id, tenantId, email, role);
    }

    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", tenantId=" + tenantId + ", role=" + role + ", enabled=" + enabled + "]";
    }
}
