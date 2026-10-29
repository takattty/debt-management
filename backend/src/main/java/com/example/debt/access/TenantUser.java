package com.example.debt.access;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class TenantUser extends User {
    private final UUID tenantId;

    public TenantUser(String username, String password, UUID tenantId) {
        super(username, password, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        this.tenantId = tenantId;
    }

    public UUID tenantId() {
        return tenantId;
    }
}
