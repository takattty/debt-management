package com.example.debt.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import com.example.debt.access.UserRole;
import com.example.debt.generated.model.InvitationAcceptRequest;
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
class InvitationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserAccounts users;
    @Autowired InvitationService invitations;
    @MockitoBean(name = "invitationClock") Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

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
    void issuingRequiresAdminAndRejectsTenantOverrideEvenForAdmin() throws Exception {
        var accountant = AuthFixtures.login(mvc, "alpha-accountant@example.test");
        String token = sessionToken(accountant);
        mvc.perform(post("/api/invitations").session(accountant).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(), "role", "ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/session").session(accountant)).andExpect(status().isOk());
        var admin = AuthFixtures.login(mvc, "alpha-admin@example.test");
        mvc.perform(post("/api/invitations").session(admin).header("X-CSRF-TOKEN", sessionToken(admin))
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(), "role", "ADMIN", "tenantId", AuthFixtures.BETA))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM invitation").query(Long.class).single()).isZero();
    }

    @Test
    void anonymousAcceptanceNeedsCsrfDoesNotAutoLoginAndEnforcesStoredRole() throws Exception {
        String email = email();
        var issued = invitations.issue(alpha(), email, UserRole.ACCOUNTANT);
        var response = new InvitationIssuedResponse(issued.invitation().id(), issued.invitation().expiresAt().atOffset(java.time.ZoneOffset.UTC),
                "/accept-invitation#token=" + issued.token().value());
        assertThat(response.toString()).doesNotContain(issued.token().value());
        String body = json.writeValueAsString(Map.of("token", issued.token().value(), "email", email, "password", AuthFixtures.PASSWORD));
        var request = json.readValue(body, InvitationAcceptRequest.class);
        assertThat(request.toString()).doesNotContain(issued.token().value(), AuthFixtures.PASSWORD);
        mvc.perform(post("/api/invitations/accept").contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var csrf = mvc.perform(get("/api/csrf")).andReturn();
        var anonymous = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        mvc.perform(post("/api/invitations/accept").session(anonymous).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ACCOUNTANT"));
        mvc.perform(get("/api/session").session(anonymous)).andExpect(status().isUnauthorized());
        assertThat(users.byEmail(email).orElseThrow().tenantId()).isEqualTo(AuthFixtures.ALPHA);
    }

    @Test
    void malformedUnknownExpiredAndUsedInvitationHaveSamePublicError() throws Exception {
        var expired = invitations.issue(alpha(), email(), UserRole.ADMIN);
        var consumed = invitations.issue(alpha(), email(), UserRole.ACCOUNTANT);
        invitations.accept(consumed.token().value(), consumed.invitation().email(), AuthFixtures.PASSWORD);
        when(clock.instant()).thenReturn(expired.invitation().expiresAt());
        var csrf = mvc.perform(get("/api/csrf")).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asString();
        String expected = null;
        for (String secret : java.util.List.of("malformed", InvitationToken.generate().value(), expired.token().value(), consumed.token().value())) {
            var result = mvc.perform(post("/api/invitations/inspect").session(session).header("X-CSRF-TOKEN", token)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("token", secret))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVITATION_INVALID")).andReturn();
            if (expected == null) {
                expected = result.getResponse().getContentAsString();
            }
            assertThat(result.getResponse().getContentAsString()).isEqualTo(expected).doesNotContain(secret, expired.invitation().email());
        }
    }

    private TenantUser alpha() {
        return TenantUser.from(users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow());
    }

    private String email() {
        return "invite-" + UUID.randomUUID() + "@example.test";
    }

    private String sessionToken(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asString();
    }
}
