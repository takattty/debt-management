package com.example.debt.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class UserAccountsCoreTest {
    @Autowired JdbcClient jdbc;
    @Autowired UserAccounts accounts;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> auditIds = new ArrayList<>();
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        for (UUID id : auditIds) {
            jdbc.sql("DELETE FROM audit_event WHERE id=?").param(id).update();
        }
        for (UUID id : userIds) {
            jdbc.sql("DELETE FROM app_user WHERE id=?").param(id).update();
        }
    }

    @Test
    void persistsHashMembershipAndRoleWithoutExposingSecretsInDiagnosticString() {
        UUID id = fixtureId();
        String email = uniqueEmail();
        String password = "𠮷".repeat(30) + " exact suffix";
        String hash = passwords.encode(password);
        insert(id, AuthFixtures.ALPHA, email, hash, "ACCOUNTANT");
        UserAccount user = accounts.byEmail(new EmailAddress(" " + email.toUpperCase(java.util.Locale.ROOT) + " ").canonical())
                .orElseThrow();
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.tenantId()).isEqualTo(AuthFixtures.ALPHA);
        assertThat(user.role()).isEqualTo(UserRole.ACCOUNTANT);
        assertThat(user.enabled()).isTrue();
        assertThat(user.passwordHash()).isEqualTo(hash).startsWith("$argon2id$");
        assertThat(passwords.matches(password, user.passwordHash())).isTrue();
        assertThat(user.toString()).doesNotContain(hash, password);
        assertThat(accounts.byId(id)).contains(user);
        assertThat(accounts.byId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void canonicalEmailIsGloballyUniqueAcrossTenants() {
        String email = new EmailAddress(" Shared-" + UUID.randomUUID() + "@EXAMPLE.TEST ").canonical();
        String hash = passwords.encode("fictional_core_password");
        UUID first = fixtureId();
        insert(first, AuthFixtures.ALPHA, email, hash, "ADMIN");
        UUID second = fixtureId();
        assertThatThrownBy(() -> insert(second, AuthFixtures.BETA, email, hash, "ACCOUNTANT"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(accounts.byEmail(email).orElseThrow().id()).isEqualTo(first);
        assertThat(accounts.byId(second)).isEmpty();
    }

    @Test
    void concurrentCanonicalEmailCreationHasExactlyOneWinner() throws Exception {
        String email = uniqueEmail();
        String hash = passwords.encode("fictional_core_password");
        UUID firstId = fixtureId();
        UUID secondId = fixtureId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentInsert(firstId, AuthFixtures.ALPHA, email, hash, ready, start));
            var second = executor.submit(() -> concurrentInsert(secondId, AuthFixtures.BETA, email, hash, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email_canonical=?").param(email).query(Long.class).single())
                .isEqualTo(1);
        assertThat(accounts.byEmail(email).orElseThrow().tenantId()).isIn(AuthFixtures.ALPHA, AuthFixtures.BETA);
    }

    @Test
    void databaseRejectsUnknownTenantRolePlaintextAndUnnormalizedEmail() {
        String hash = passwords.encode("fictional_core_password");
        assertThatThrownBy(() -> insert(fixtureId(), UUID.randomUUID(), uniqueEmail(), hash, "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(fixtureId(), AuthFixtures.ALPHA, uniqueEmail(), hash, "OTHER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(fixtureId(), AuthFixtures.ALPHA, uniqueEmail(), "plaintext rejected", "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(fixtureId(), AuthFixtures.ALPHA, "Uppercase@EXAMPLE.TEST", hash, "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditUserMustBelongToEventTenantWhileLegacyActorsRemainValid() {
        UUID user = fixtureId();
        insert(user, AuthFixtures.ALPHA, uniqueEmail(), passwords.encode("fictional_core_password"), "ADMIN");
        UUID ownEvent = auditId();
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,?,?, '{}'::jsonb)
                """).params(ownEvent, AuthFixtures.ALPHA, "user:" + user, user, "CORE_FIXTURE", user).update();
        UUID crossedEvent = auditId();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,?,?, '{}'::jsonb)
                """).params(crossedEvent, AuthFixtures.BETA, "user:" + user, user, "CORE_FIXTURE", user).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID legacyEvent = auditId();
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,action,entity_id,details)
                VALUES (?,?,?,?,?, '{}'::jsonb)
                """).params(legacyEvent, AuthFixtures.ALPHA, "legacy-dev-actor", "CORE_FIXTURE", user).update();
        assertThat(jdbc.sql("SELECT actor_user_id IS NULL FROM audit_event WHERE id=?")
                .param(legacyEvent).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT actor_user_id FROM audit_event WHERE id=?")
                .param(ownEvent).query(UUID.class).single()).isEqualTo(user);
    }

    private boolean concurrentInsert(UUID id, UUID tenant, String email, String hash,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent fixture start timed out");
        }
        try {
            insert(id, tenant, email, hash, "ADMIN");
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    private void insert(UUID id, UUID tenant, String email, String hash, String role) {
        jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,?,?)")
                .params(id, tenant, email, hash, role).update();
    }

    private UUID fixtureId() {
        UUID id = UUID.randomUUID();
        userIds.add(id);
        return id;
    }

    private UUID auditId() {
        UUID id = UUID.randomUUID();
        auditIds.add(id);
        return id;
    }

    private String uniqueEmail() {
        return UUID.randomUUID() + "@example.test";
    }
}
