package com.example.debt.invitation;

import com.example.debt.access.EmailAddress;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.access.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    public record Issued(Invitation invitation, InvitationToken token) {}

    private final Invitations invitations;
    private final UserAccounts users;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InvitationService(Invitations invitations, UserAccounts users, JdbcClient jdbc,
            @Qualifier("invitationClock") Clock clock) {
        this.invitations = invitations;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Issued issue(TenantUser actor, String email, UserRole role) {
        if (actor.role() != UserRole.ADMIN) {
            throw new AccessDeniedException("Administrator membership is required");
        }
        String canonical = new EmailAddress(email).canonical();
        if (users.byEmail(canonical).isPresent()) {
            throw new InvitationUnavailable();
        }
        InvitationToken token = InvitationToken.generate();
        Instant created = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Invitation invitation = new Invitation(UUID.randomUUID(), actor.tenantId(), canonical, role, token.hash(),
                actor.userId(), created, created.plus(24, ChronoUnit.HOURS), null, null);
        invitations.insert(invitation);
        audit(invitation, actor.userId(), "MEMBER_INVITED");
        return new Issued(invitation, token);
    }

    private void audit(Invitation invitation, UUID actorId, String action) {
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (:id,:tenant,:actor,:user,:action,:entity,jsonb_build_object('role',CAST(:role AS text)))
                """).param("id", UUID.randomUUID()).param("tenant", invitation.tenantId()).param("actor", "user:" + actorId)
                .param("user", actorId).param("action", action).param("entity", invitation.id())
                .param("role", invitation.role().name()).update();
    }
}
