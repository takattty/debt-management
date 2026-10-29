package com.example.debt.access;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccounts {
    private final JdbcClient jdbc;

    public UserAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> byEmail(String email) {
        return jdbc.sql("SELECT id, tenant_id, email_canonical, password_hash, role, enabled FROM app_user WHERE email_canonical = ?")
                .param(email).query((rs, row) -> new UserAccount(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("email_canonical"),
                        rs.getString("password_hash"), UserRole.valueOf(rs.getString("role")), rs.getBoolean("enabled")))
                .optional();
    }

    public Optional<UserAccount> byId(UUID id) {
        return jdbc.sql("SELECT id, tenant_id, email_canonical, password_hash, role, enabled FROM app_user WHERE id = ?")
                .param(id).query((rs, row) -> new UserAccount(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("email_canonical"),
                        rs.getString("password_hash"), UserRole.valueOf(rs.getString("role")), rs.getBoolean("enabled")))
                .optional();
    }
}
