package com.example.debt.invitation;

import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class Invitations {
    private final JdbcClient jdbc;

    public Invitations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Invitation invitation) {
        jdbc.sql("""
                INSERT INTO invitation(id,tenant_id,email_canonical,role,token_hash,created_by_user_id,created_at,expires_at)
                VALUES (:id,:tenant,:email,:role,:hash,:creator,:created,:expires)
                """).param("id", invitation.id()).param("tenant", invitation.tenantId()).param("email", invitation.email())
                .param("role", invitation.role().name()).param("hash", invitation.tokenHash()).param("creator", invitation.createdByUserId())
                .param("created", invitation.createdAt().atOffset(ZoneOffset.UTC))
                .param("expires", invitation.expiresAt().atOffset(ZoneOffset.UTC)).update();
    }

}
