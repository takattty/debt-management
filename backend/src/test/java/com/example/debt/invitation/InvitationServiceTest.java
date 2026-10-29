package com.example.debt.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.access.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvitationServiceTest {
    @Autowired InvitationService service;
    @Autowired Invitations invitations;
    @Autowired UserAccounts users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcClient jdbc;
    @MockitoBean(name = "invitationClock") Clock clock;
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String PASSWORD = "𠮷".repeat(30) + " password with exact suffix  ";

    @BeforeEach
    void resetDedicatedTestFixtures() {
        jdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM invitation").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM app_user WHERE id NOT IN (?,?,?)")
                .params(AuthFixtures.ALPHA_ADMIN, AuthFixtures.BETA_ADMIN, AuthFixtures.ALPHA_ACCOUNTANT).update();
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void issuesHashOnlyAndAcceptsStoredTenantAndSelectedRoleExactlyOnce() {
        String email = email();
        var issued = service.issue(alpha(), " " + email.toUpperCase(java.util.Locale.ROOT) + " ", UserRole.ADMIN);
        assertThat(issued.invitation().expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(invitations.findByHash(issued.token().hash(), false).orElseThrow().email()).isEqualTo(email);
        assertThat(jdbc.sql("SELECT token_hash FROM invitation WHERE id=?").param(issued.invitation().id()).query(String.class).single())
                .isEqualTo(issued.token().hash()).doesNotContain(issued.token().value());
        var preview = service.inspect(issued.token().value());
        assertThat(preview.maskedEmail()).isEqualTo("***@example.test");
        assertThat(preview.role()).isEqualTo(UserRole.ADMIN);
        var accepted = service.accept(issued.token().value(), email, PASSWORD);
        var account = users.byId(accepted.userId()).orElseThrow();
        assertThat(account.tenantId()).isEqualTo(AuthFixtures.ALPHA);
        assertThat(account.role()).isEqualTo(UserRole.ADMIN);
        assertThat(passwords.matches(PASSWORD, account.passwordHash())).isTrue();
        assertThat(passwords.matches(PASSWORD.strip(), account.passwordHash())).isFalse();
        assertThatThrownBy(() -> service.accept(issued.token().value(), email, PASSWORD)).isInstanceOf(InvitationInvalid.class);
        assertThatThrownBy(() -> service.inspect(issued.token().value())).isInstanceOf(InvitationInvalid.class);
        assertThat(auditCount("INVITATION_ACCEPTED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT details::text FROM audit_event").query(String.class).list())
                .allSatisfy(details -> assertThat(details).doesNotContain(issued.token().value(), issued.token().hash(), PASSWORD, account.passwordHash()));
    }

    @Test
    void rejectsAccountantAndExistingGlobalEmailWithoutChangingState() {
        TenantUser accountant = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
        assertThatThrownBy(() -> service.issue(accountant, email(), UserRole.ADMIN)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.issue(alpha(), "beta-admin@example.test", UserRole.ACCOUNTANT))
                .isInstanceOf(InvitationUnavailable.class);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
        assertThat(auditCount("MEMBER_INVITED")).isZero();
    }

    @Test
    void rejectsExpiryAtBoundaryMismatchAndUnknownTokensWithoutConsumingInvitation() {
        String email = email();
        var issued = service.issue(alpha(), email, UserRole.ACCOUNTANT);
        assertThatThrownBy(() -> service.accept(issued.token().value(), email(), PASSWORD)).isInstanceOf(InvitationInvalid.class);
        assertThatThrownBy(() -> service.inspect("malformed")).isInstanceOf(InvitationInvalid.class);
        assertThatThrownBy(() -> service.inspect(InvitationToken.generate().value())).isInstanceOf(InvitationInvalid.class);
        when(clock.instant()).thenReturn(issued.invitation().expiresAt());
        assertThatThrownBy(() -> service.inspect(issued.token().value())).isInstanceOf(InvitationInvalid.class);
        assertThatThrownBy(() -> service.accept(issued.token().value(), email, PASSWORD)).isInstanceOf(InvitationInvalid.class);
        assertThat(users.byEmail(email)).isEmpty();
        assertThat(invitations.findByHash(issued.token().hash(), false).orElseThrow().acceptedAt()).isNull();
        assertThat(auditCount("INVITATION_ACCEPTED")).isZero();
    }

    @Test
    void deadlineIsRecheckedAfterUserInsertAndRollsBackIfItPasses() {
        String email = email();
        var issued = service.issue(alpha(), email, UserRole.ACCOUNTANT);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1), issued.invitation().expiresAt());
        assertThatThrownBy(() -> service.accept(issued.token().value(), email, PASSWORD)).isInstanceOf(InvitationInvalid.class);
        assertThat(users.byEmail(email)).isEmpty();
        assertThat(invitations.findByHash(issued.token().hash(), false).orElseThrow().acceptedUserId()).isNull();
        assertThat(auditCount("INVITATION_ACCEPTED")).isZero();
    }

    @Test
    void concurrentSameTokenHasOneUserOneConsumptionAndOneAcceptanceAudit() throws Exception {
        String email = email();
        var issued = service.issue(alpha(), email, UserRole.ACCOUNTANT);
        List<String> results = race(() -> acceptOutcome(issued.token().value(), email),
                () -> acceptOutcome(issued.token().value(), email));
        assertThat(results).containsExactlyInAnyOrder("accepted", "invalid");
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email_canonical=?").param(email).query(Long.class).single()).isEqualTo(1);
        assertThat(auditCount("INVITATION_ACCEPTED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE accepted_at IS NOT NULL").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSameEmailAcrossTenantsKeepsLoserUnconsumedAndCreatesOnlyOneUser() throws Exception {
        String email = email();
        var alphaInvite = service.issue(alpha(), email, UserRole.ADMIN);
        TenantUser beta = TenantUser.from(users.byId(AuthFixtures.BETA_ADMIN).orElseThrow());
        var betaInvite = service.issue(beta, email, UserRole.ACCOUNTANT);
        List<String> results = race(() -> acceptOutcome(alphaInvite.token().value(), email),
                () -> acceptOutcome(betaInvite.token().value(), email));
        assertThat(results).containsExactlyInAnyOrder("accepted", "conflict");
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email_canonical=?").param(email).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE accepted_at IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE accepted_at IS NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(auditCount("INVITATION_ACCEPTED")).isEqualTo(1);
        var winner = users.byEmail(email).orElseThrow();
        assertThat(winner.tenantId()).isIn(AuthFixtures.ALPHA, AuthFixtures.BETA);
        assertThat(winner.role()).isEqualTo(winner.tenantId().equals(AuthFixtures.ALPHA) ? UserRole.ADMIN : UserRole.ACCOUNTANT);
    }

    @Test
    void issuanceAuditFailureRollsBackInvitation() {
        rejectInvitationAudits();
        try {
            assertThatThrownBy(() -> service.issue(alpha(), email(), UserRole.ADMIN)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
            assertThat(auditCount("MEMBER_INVITED")).isZero();
        } finally {
            allowInvitationAudits();
        }
    }

    @Test
    void acceptanceAuditFailureRollsBackUserConsumptionAndPermitsSafeRetry() {
        String email = email();
        var issued = service.issue(alpha(), email, UserRole.ACCOUNTANT);
        rejectInvitationAudits();
        try {
            assertThatThrownBy(() -> service.accept(issued.token().value(), email, PASSWORD)).isInstanceOf(DataAccessException.class);
            assertThat(users.byEmail(email)).isEmpty();
            assertThat(invitations.findByHash(issued.token().hash(), false).orElseThrow().acceptedAt()).isNull();
            assertThat(auditCount("INVITATION_ACCEPTED")).isZero();
        } finally {
            allowInvitationAudits();
        }
        assertThat(service.accept(issued.token().value(), email, PASSWORD).email()).isEqualTo(email);
        assertThat(auditCount("INVITATION_ACCEPTED")).isEqualTo(1);
    }

    private TenantUser alpha() {
        return TenantUser.from(users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow());
    }

    private String email() {
        return "invite-" + UUID.randomUUID() + "@example.test";
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action=?").param(action).query(Long.class).single();
    }

    private String acceptOutcome(String token, String email) {
        try {
            service.accept(token, email, PASSWORD);
            return "accepted";
        } catch (InvitationInvalid expected) {
            return "invalid";
        } catch (InvitationUnavailable expected) {
            return "conflict";
        }
    }

    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var two = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
    }

    private void rejectInvitationAudits() {
        jdbc.sql("""
                CREATE FUNCTION reject_invitation_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.action IN ('MEMBER_INVITED','INVITATION_ACCEPTED') THEN RAISE EXCEPTION 'Rejected test audit'; END IF;
                  RETURN NEW;
                END $$
                """).update();
        jdbc.sql("CREATE TRIGGER reject_invitation_test_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_invitation_test_audit()")
                .update();
    }

    private void allowInvitationAudits() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_invitation_test_audit ON audit_event").update();
        jdbc.sql("DROP FUNCTION IF EXISTS reject_invitation_test_audit()").update();
    }
}
