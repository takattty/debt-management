package com.example.debt.access;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionAudit {
    private final JdbcClient jdbc;

    public SessionAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void record(TenantUser user, String action) {
        jdbc.sql("""
                INSERT INTO audit_event(id, tenant_id, actor, actor_user_id, action, entity_id, details)
                VALUES (:id, :tenant, :actor, :user, :action, :user, '{}'::jsonb)
                """)
                .param("id", UUID.randomUUID()).param("tenant", user.tenantId())
                .param("actor", "user:" + user.userId()).param("user", user.userId()).param("action", action).update();
    }
}
