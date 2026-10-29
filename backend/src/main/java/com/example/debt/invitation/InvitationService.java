package com.example.debt.invitation;

import com.example.debt.access.EmailAddress;
import com.example.debt.access.PasswordPolicy;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.access.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    public record Issued(Invitation invitation, InvitationToken token) {}
    public record Preview(String tenantDisplayName, String maskedEmail, UserRole role, Instant expiresAt) {}
    public record Accepted(UUID userId, String email, UserRole role) {}

    private final Invitations invitations;
    private final UserAccounts users;
    private final PasswordEncoder passwords;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InvitationService(Invitations invitations, UserAccounts users, PasswordEncoder passwords, JdbcClient jdbc,
            @Qualifier("invitationClock") Clock clock) {
        this.invitations = invitations;
        this.users = users;
        this.passwords = passwords;
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

    @Transactional(readOnly = true)
    public Preview inspect(String token) {
        Invitation invitation = available(token, false);
        if (users.byEmail(invitation.email()).isPresent()) {
            throw new InvitationInvalid();
        }
        String masked = "***" + invitation.email().substring(invitation.email().lastIndexOf('@'));
        return new Preview(invitations.tenantName(invitation.tenantId()), masked, invitation.role(), invitation.expiresAt());
    }

    @Transactional
    public Accepted accept(String token, String email, String password) {
        PasswordPolicy.validateCreation(password);
        String canonical = new EmailAddress(email).canonical();
        Invitation invitation = available(token, true);
        if (!invitation.email().equals(canonical)) {
            throw new InvitationInvalid();
        }
        UUID userId = UUID.randomUUID();
        String hash = passwords.encode(password);
        try {
            jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,?,?)")
                    .params(userId, invitation.tenantId(), canonical, hash, invitation.role().name()).update();
        } catch (DuplicateKeyException conflict) {
            throw new InvitationUnavailable();
        }
        // Check the current deadline again after hashing / unique-index waits, immediately before consumption.
        Instant accepted = clock.instant();
        if (!invitation.isAvailableAt(accepted)
                || !invitations.consume(invitation, userId, accepted.truncatedTo(ChronoUnit.MICROS))) {
            throw new InvitationInvalid();
        }
        audit(invitation, userId, "INVITATION_ACCEPTED");
        return new Accepted(userId, canonical, invitation.role());
    }

    private Invitation available(String value, boolean lock) {
        String hash;
        try {
            hash = new InvitationToken(value).hash();
        } catch (IllegalArgumentException invalid) {
            throw new InvitationInvalid();
        }
        Invitation invitation = invitations.findByHash(hash, lock).orElseThrow(InvitationInvalid::new);
        if (!invitation.isAvailableAt(clock.instant())) {
            throw new InvitationInvalid();
        }
        return invitation;
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
