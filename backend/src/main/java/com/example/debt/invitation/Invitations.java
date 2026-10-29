package com.example.debt.invitation;

import com.example.debt.access.UserRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class Invitations {
    private final JdbcClient jdbc;

    public Invitations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Anonymous lookup is authorized by the unguessable bearer secret, rather than a client tenant ID. */
    public Optional<Invitation> findByHash(String hash, boolean lock) {
        return jdbc.sql("SELECT * FROM invitation WHERE token_hash=:hash" + (lock ? " FOR UPDATE" : ""))
                .param("hash", hash).query((rs, row) -> {
                    OffsetDateTime accepted = rs.getObject("accepted_at", OffsetDateTime.class);
                    return new Invitation(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getString("email_canonical"), UserRole.valueOf(rs.getString("role")), rs.getString("token_hash"),
                            rs.getObject("created_by_user_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant(), accepted == null ? null : accepted.toInstant(),
                            rs.getObject("accepted_user_id", UUID.class));
                }).optional();
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

    public boolean consume(Invitation invitation, UUID userId, Instant acceptedAt) {
        return jdbc.sql("""
                UPDATE invitation SET accepted_at=:accepted,accepted_user_id=:user
                WHERE id=:id AND tenant_id=:tenant AND accepted_at IS NULL
                  AND created_at<=:accepted AND expires_at>:accepted
                """).param("id", invitation.id()).param("tenant", invitation.tenantId()).param("user", userId)
                .param("accepted", acceptedAt.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    public String tenantName(UUID tenantId) {
        return jdbc.sql("SELECT name FROM tenant WHERE id=?").param(tenantId).query(String.class).single();
    }
}
