package com.example.debt.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.access.UserRole;
import com.example.debt.generated.model.InvitationIssuedResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class InvitationIssuanceTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired InvitationService service;
    @Autowired UserAccounts users;
    @Autowired JsonMapper json;
    @MockitoBean(name = "invitationClock") Clock clock;
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    @BeforeEach
    void resetDedicatedTestFixtures() {
        jdbc.sql("DELETE FROM audit_event").update();
        jdbc.sql("DELETE FROM invitation").update();
        jdbc.sql("DELETE FROM customer").update();
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void issuesBothRolesWithFixedTenantDeadlineHashOnlyAndSecretRedactedDiagnostics() throws Exception {
        var session = AuthFixtures.login(mvc, "alpha-admin@example.test");
        String csrf = csrf(session);
        for (UserRole role : UserRole.values()) {
            String email = email();
            var result = mvc.perform(post("/api/invitations").session(session).header("X-CSRF-TOKEN", csrf).header("X-Tenant-Id", AuthFixtures.BETA)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("email", email, "role", role.name()))))
                    .andExpect(status().isCreated()).andReturn();
            var response = json.readValue(result.getResponse().getContentAsString(), InvitationIssuedResponse.class);
            String token = response.getAcceptanceUrl().substring("/accept-invitation#token=".length());
            assertThat(response.getAcceptanceUrl()).startsWith("/accept-invitation#token=");
            assertThat(response.getExpiresAt().toInstant()).isEqualTo(NOW.plusSeconds(86400));
            assertThat(response.toString()).doesNotContain(token);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            var persisted = jdbc.sql("SELECT tenant_id,email_canonical,role,token_hash,created_by_user_id FROM invitation WHERE id=?")
                    .param(response.getId()).query().singleRow();
            assertThat(persisted.get("tenant_id")).isEqualTo(AuthFixtures.ALPHA);
            assertThat(persisted.get("created_by_user_id")).isEqualTo(AuthFixtures.ALPHA_ADMIN);
            assertThat(persisted.get("role")).isEqualTo(role.name());
            assertThat(persisted.get("email_canonical")).isEqualTo(email);
            assertThat(persisted.get("token_hash")).isEqualTo(new InvitationToken(token).hash());
            String details = jdbc.sql("SELECT details::text FROM audit_event WHERE entity_id=? AND action='MEMBER_INVITED'")
                    .param(response.getId()).query(String.class).single();
            assertThat(details).contains(role.name()).doesNotContain(token, new InvitationToken(token).hash());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='MEMBER_INVITED' AND actor_user_id=?")
                .param(AuthFixtures.ALPHA_ADMIN).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void requiresAdminAndCsrfAndRejectsClientTenantOverride() throws Exception {
        var accountant = AuthFixtures.login(mvc, "alpha-accountant@example.test");
        mvc.perform(post("/api/invitations").session(accountant).header("X-CSRF-TOKEN", csrf(accountant))
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(), "role", "ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/session").session(accountant)).andExpect(status().isOk());
        var admin = AuthFixtures.login(mvc, "alpha-admin@example.test");
        mvc.perform(post("/api/invitations").session(admin).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email(), "role", "ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/invitations").session(admin).header("X-CSRF-TOKEN", csrf(admin))
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(), "role", "ADMIN", "tenantId", AuthFixtures.BETA))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void existingOtherTenantEmailReturnsGenericConflictWithoutMembershipDetails() throws Exception {
        var admin = AuthFixtures.login(mvc, "alpha-admin@example.test");
        var result = mvc.perform(post("/api/invitations").session(admin).header("X-CSRF-TOKEN", csrf(admin))
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", "beta-admin@example.test", "role", "ACCOUNTANT"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("beta-admin@example.test", AuthFixtures.BETA.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='MEMBER_INVITED'").query(Long.class).single()).isZero();
    }

    @Test
    void auditFailureRollsBackInvitationAndCreatesNoSuccessfulAudit() {
        jdbc.sql("""
                CREATE FUNCTION reject_issuance_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.action='MEMBER_INVITED' THEN RAISE EXCEPTION 'Rejected test audit'; END IF;
                  RETURN NEW;
                END $$
                """).update();
        jdbc.sql("CREATE TRIGGER reject_issuance_test_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_issuance_test_audit()")
                .update();
        try {
            var actor = TenantUser.from(users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow());
            assertThatThrownBy(() -> service.issue(actor, email(), UserRole.ADMIN)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action='MEMBER_INVITED'").query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS reject_issuance_test_audit ON audit_event").update();
            jdbc.sql("DROP FUNCTION IF EXISTS reject_issuance_test_audit()").update();
        }
    }

    private String csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asString();
    }

    private String email() {
        return "invite-" + UUID.randomUUID() + "@example.test";
    }
}
