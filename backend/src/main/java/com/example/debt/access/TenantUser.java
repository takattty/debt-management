package com.example.debt.access;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request snapshot; membership is reloaded from the database before every protected request. */
public record TenantUser(UUID userId, UUID tenantId, String email, UserRole role) implements Serializable {
    public static TenantUser from(UserAccount account) {
        return new TenantUser(account.id(), account.tenantId(), account.email(), account.role());
    }

    public List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String toString() {
        return "TenantUser[userId=" + userId + ", role=" + role + "]";
    }
}
