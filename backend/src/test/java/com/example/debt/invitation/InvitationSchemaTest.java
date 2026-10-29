package com.example.debt.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.debt.access.AuthFixtures;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvitationSchemaTest {
    @Autowired JdbcClient jdbc;
    private final List<UUID> ids = new ArrayList<>();
    private static final Instant CREATED = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(86400);

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        for (UUID id : ids) {
            jdbc.sql("DELETE FROM invitation WHERE id=?").param(id).update();
        }
    }

    @Test
    void persistsOnlyHashAndRequiresIssuerMembershipInInvitationTenant() {
        InvitationToken token = InvitationToken.generate();
        UUID id = id();
        insert(id, AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, "ADMIN", token.hash(), EXPIRES);
        assertThat(jdbc.sql("SELECT token_hash FROM invitation WHERE id=?").param(id).query(String.class).single())
                .isEqualTo(token.hash()).doesNotContain(token.value());
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='invitation'")
                .query(String.class).list()).doesNotContain("token", "token_value", "password");
        assertThatThrownBy(() -> insert(id(), AuthFixtures.ALPHA, AuthFixtures.BETA_ADMIN,
                "ADMIN", InvitationToken.generate().hash(), EXPIRES)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateHashUnknownRoleAndNonFutureDeadline() {
        String hash = InvitationToken.generate().hash();
        insert(id(), AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, "ACCOUNTANT", hash, EXPIRES);
        assertThatThrownBy(() -> insert(id(), AuthFixtures.BETA, AuthFixtures.BETA_ADMIN, "ADMIN", hash, EXPIRES))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insert(id(), AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN,
                "OWNER", InvitationToken.generate().hash(), EXPIRES)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(id(), AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN,
                "ADMIN", InvitationToken.generate().hash(), CREATED)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(id(), AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN,
                "ADMIN", "raw-secret-rejected", EXPIRES)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptanceIsPairedBeforeDeadlineAndBoundToSameTenantUser() {
        UUID id = id();
        insert(id, AuthFixtures.ALPHA, AuthFixtures.ALPHA_ADMIN, "ACCOUNTANT", InvitationToken.generate().hash(), EXPIRES);
        assertThatThrownBy(() -> jdbc.sql("UPDATE invitation SET accepted_user_id=? WHERE id=?")
                .params(AuthFixtures.ALPHA_ACCOUNTANT, id).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE invitation SET accepted_at=?,accepted_user_id=? WHERE id=?")
                .params(CREATED.plusSeconds(1).atOffset(ZoneOffset.UTC), AuthFixtures.BETA_ADMIN, id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE invitation SET accepted_at=?,accepted_user_id=? WHERE id=?")
                .params(EXPIRES.atOffset(ZoneOffset.UTC), AuthFixtures.ALPHA_ACCOUNTANT, id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE invitation SET accepted_at=?,accepted_user_id=? WHERE id=?")
                .params(CREATED.plusSeconds(1).atOffset(ZoneOffset.UTC), AuthFixtures.ALPHA_ACCOUNTANT, id).update();
        assertThat(jdbc.sql("SELECT accepted_user_id FROM invitation WHERE id=?").param(id).query(UUID.class).single())
                .isEqualTo(AuthFixtures.ALPHA_ACCOUNTANT);
    }

    private void insert(UUID id, UUID tenant, UUID creator, String role, String hash, Instant expires) {
        jdbc.sql("""
                INSERT INTO invitation(id,tenant_id,email_canonical,role,token_hash,created_by_user_id,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """).params(id, tenant, "fictional@example.test", role, hash, creator,
                        CREATED.atOffset(ZoneOffset.UTC), expires.atOffset(ZoneOffset.UTC)).update();
    }

    private UUID id() {
        UUID id = UUID.randomUUID();
        ids.add(id);
        return id;
    }
}
