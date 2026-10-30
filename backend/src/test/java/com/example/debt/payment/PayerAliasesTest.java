package com.example.debt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.debt.access.AuthFixtures;
import com.example.debt.access.TenantUser;
import com.example.debt.access.UserAccounts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@Import(AuthFixtures.class)
class PayerAliasesTest {
    @Autowired PayerAliases aliases;
    @Autowired JdbcClient jdbc;
    @Autowired UserAccounts users;
    @Autowired MockMvc mvc;
    private TenantUser accountant;
    private TenantUser admin;
    private TenantUser beta;
    private UUID first;
    private UUID second;
    private UUID foreign;

    @BeforeEach
    void fixtures() {
        accountant = TenantUser.from(users.byId(AuthFixtures.ALPHA_ACCOUNTANT).orElseThrow());
        admin = TenantUser.from(users.byId(AuthFixtures.ALPHA_ADMIN).orElseThrow());
        beta = TenantUser.from(users.byId(AuthFixtures.BETA_ADMIN).orElseThrow());
        first = customer(AuthFixtures.ALPHA); second = customer(AuthFixtures.ALPHA); foreign = customer(AuthFixtures.BETA);
    }

    @AfterEach
    void removeOnlyTestAliasesAndOwnedCustomers() {
        jdbc.sql("TRUNCATE payer_alias").update();
        jdbc.sql("DELETE FROM audit_event WHERE action LIKE 'PAYER_ALIAS_%'").update();
        jdbc.sql("DELETE FROM customer WHERE id IN (?,?,?)").params(first, second, foreign).update();
    }

    @Test
    void supportsSameNameForMultipleCustomersAndRetainsDeactivatedRegistrationOnReregister() {
        var original = aliases.register(accountant, "　架空名義　", first);
        var ambiguous = aliases.register(admin, "架空名義", second);
        assertThatThrownBy(() -> aliases.register(admin, "架空名義 ", first)).hasMessage("PAYER_ALIAS_EXISTS");
        var disabled = aliases.deactivate(admin, original.id(), 0, " 顧客の選択誤り ");
        assertThat(disabled.active()).isFalse(); assertThat(disabled.version()).isEqualTo(1);
        assertThat(disabled.createdByUserId()).isEqualTo(accountant.userId());
        assertThat(disabled.createdAt()).isEqualTo(original.createdAt());
        assertThat(disabled.deactivatedByUserId()).isEqualTo(admin.userId());
        assertThat(disabled.deactivationReason()).isEqualTo("顧客の選択誤り");
        assertThat(aliases.get(admin.tenantId(), original.id())).isEqualTo(disabled);
        var replacement = aliases.register(accountant, "架空名義", first);
        assertThat(replacement.id()).isNotEqualTo(original.id());
        assertThat(aliases.list(admin.tenantId(), null, 100).items()).containsExactlyInAnyOrder(ambiguous, replacement);
        assertThat(count("payer_alias")).isEqualTo(3);
        assertThat(count("audit_event WHERE action LIKE 'PAYER_ALIAS_%'")).isEqualTo(4);
        assertThatThrownBy(() -> aliases.deactivate(admin, original.id(), 1, "再送")).hasMessage("VERSION_CONFLICT");
    }

    @Test
    void separatesTenantsReferencesActorsAndImmutableHistoryAtDatabaseBoundary() {
        var original = aliases.register(accountant, "架空", first);
        assertThat(aliases.list(beta.tenantId(), null, 100).items()).isEmpty();
        assertThatThrownBy(() -> aliases.get(beta.tenantId(), original.id())).isInstanceOf(PayerAliases.Missing.class);
        assertThatThrownBy(() -> aliases.deactivate(beta, original.id(), 0, "越境")).isInstanceOf(PayerAliases.Missing.class);
        assertThatThrownBy(() -> aliases.register(accountant, "越境", foreign)).isInstanceOf(PayerAliases.Missing.class);
        assertThatThrownBy(() -> insertRaw(foreign, accountant.userId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRaw(first, beta.userId())).isInstanceOf(DataAccessException.class);
        for (String statement : List.of("DELETE FROM payer_alias WHERE id=?",
                "UPDATE payer_alias SET payer_name='改名' WHERE id=?", "UPDATE payer_alias SET customer_id='" + second + "' WHERE id=?")) {
            assertThatThrownBy(() -> jdbc.sql(statement).param(original.id()).update()).isInstanceOf(DataAccessException.class);
        }
        aliases.register(beta, "架空", foreign);
        assertThat(aliases.list(beta.tenantId(), null, 100).items()).hasSize(1);
    }

    @Test
    void concurrentRegistrationAndDeactivationHaveOneWinnerAndOneAudit() throws Exception {
        var registration = race(() -> aliases.register(accountant, "競合", first), () -> aliases.register(admin, "競合", first));
        assertThat(registration.stream().filter(PayerAliases.Row.class::isInstance)).hasSize(1);
        assertThat(registration.stream().filter(PayerAliases.Conflict.class::isInstance)).hasSize(1);
        var row = aliases.list(admin.tenantId(), null, 100).items().getFirst();
        var deactivation = race(() -> aliases.deactivate(accountant, row.id(), 0, "誤登録"), () -> aliases.deactivate(admin, row.id(), 0, "重複判断"));
        assertThat(deactivation.stream().filter(PayerAliases.Row.class::isInstance)).hasSize(1);
        assertThat(deactivation.stream().filter(PayerAliases.Conflict.class::isInstance)).hasSize(1);
        assertThat(count("payer_alias")).isEqualTo(1);
        assertThat(count("audit_event WHERE action LIKE 'PAYER_ALIAS_%'")).isEqualTo(2);
    }

    @Test
    void auditFailureRollsBackRegistrationAndDeactivation() {
        rejectAudits();
        try {
            assertThatThrownBy(() -> aliases.register(accountant, "rollback", first)).isInstanceOf(DataAccessException.class);
            assertThat(count("payer_alias")).isZero();
        } finally { allowAudits(); }
        var row = aliases.register(accountant, "rollback", first);
        rejectAudits();
        try {
            assertThatThrownBy(() -> aliases.deactivate(admin, row.id(), 0, "rollback")).isInstanceOf(DataAccessException.class);
            assertThat(aliases.get(admin.tenantId(), row.id())).isEqualTo(row);
            assertThat(count("audit_event WHERE action LIKE 'PAYER_ALIAS_%'")).isEqualTo(1);
        } finally { allowAudits(); }
    }

    @Test
    void pagesActiveRowsOnlyAndPreservesDistinctUnicodeNames() {
        for (String name : List.of("ｶｸｳ", "カクウ", "KAKUU", "kakuu", "é", "e\u0301")) { aliases.register(accountant, name, first); }
        var page = aliases.list(admin.tenantId(), null, 1);
        var next = aliases.list(admin.tenantId(), page.nextCursor(), 100);
        assertThat(next.items()).hasSize(5).doesNotContain(page.items().getFirst());
        assertThat(next.nextCursor()).isNull();
        assertThatThrownBy(() -> aliases.list(admin.tenantId(), null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aliases.deactivate(admin, page.items().getFirst().id(), 1, "古い版")).hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(() -> aliases.register(admin, "\t名義", first)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpEnforcesRolesCsrfUnknownFieldsAndTenantBeforeReturningHistory() throws Exception {
        var session = AuthFixtures.login(mvc, "alpha-accountant@example.test");
        var betaSession = AuthFixtures.login(mvc, "beta-admin@example.test");
        String body = JsonMapper.builder().build().writeValueAsString(Map.of("payerName", " HTTP名義 ", "customerId", first));
        mvc.perform(get("/api/payer-aliases")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payer-aliases").session(session).contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/payer-aliases").session(session).with(csrf()).contentType("application/json")
                        .content(body.substring(0, body.length() - 1) + ",\"tenantId\":\"" + AuthFixtures.BETA + "\"}"))
                .andExpect(status().isBadRequest());
        String response = mvc.perform(post("/api/payer-aliases").session(session).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.payerName").value("HTTP名義"))
                .andExpect(jsonPath("$.createdByUserId").value(accountant.userId().toString())).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonMapper.builder().build().readTree(response).get("id").asString());
        mvc.perform(get("/api/payer-aliases/" + id).session(betaSession).header("X-Tenant-Id", AuthFixtures.ALPHA))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/payer-aliases/" + id + "/deactivation").session(session).with(csrf())
                        .contentType("application/json").content("{\"version\":0,\"reason\":\"誤登録\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/payer-aliases/" + id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivationReason").value("誤登録"));
        mvc.perform(get("/api/payer-aliases").session(session)).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void httpUsesCodePointBoundsAfterStrippingAndRejectsNonStringJson() throws Exception {
        var session = AuthFixtures.login(mvc, "alpha-accountant@example.test");
        var json = JsonMapper.builder().build();
        String name = "😀".repeat(200), reason = "😀".repeat(500);
        String created = mvc.perform(post("/api/payer-aliases").session(session).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "payerName", "　" + name + "　", "customerId", first))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.payerName").value(name))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).get("id").asString());
        mvc.perform(post("/api/payer-aliases/" + id + "/deactivation").session(session).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "version", 0, "reason", "　" + reason + "　"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deactivationReason").value(reason));
        mvc.perform(get("/api/payer-aliases/" + id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.payerName").value(name)).andExpect(jsonPath("$.deactivationReason").value(reason));
        for (String value : List.of("0", "true", "null", "[]", "{}", json.writeValueAsString("😀".repeat(201)))) {
            mvc.perform(post("/api/payer-aliases").session(session).with(csrf()).contentType("application/json")
                            .content("{\"payerName\":" + value + ",\"customerId\":\"" + first + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        for (String value : List.of("0", "true", "null", "[]", "{}", json.writeValueAsString("😀".repeat(501)))) {
            mvc.perform(post("/api/payer-aliases/" + id + "/deactivation").session(session).with(csrf())
                            .contentType("application/json").content("{\"version\":1,\"reason\":" + value + "}"))
                    .andExpect(status().isBadRequest());
        }
        for (String version : List.of("\"0\"", "0.5", "true", "null")) {
            mvc.perform(post("/api/payer-aliases/" + id + "/deactivation").session(session).with(csrf())
                            .contentType("application/json").content("{\"version\":" + version + ",\"reason\":\"誤登録\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("payer_alias")).isEqualTo(1);
        assertThat(count("audit_event WHERE action LIKE 'PAYER_ALIAS_%'")).isEqualTo(2);
    }

    private UUID customer(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,?)").params(id, tenant, "ALIAS-" + id, "架空顧客").update();
        return id;
    }
    private long count(String source) { return jdbc.sql("SELECT count(*) FROM " + source).query(Long.class).single(); }
    private void insertRaw(UUID customer, UUID actor) {
        jdbc.sql("INSERT INTO payer_alias(id,tenant_id,payer_name,customer_id,created_by_user_id) VALUES (?,?,?,?,?)")
                .params(UUID.randomUUID(), accountant.tenantId(), "raw", customer, actor).update();
    }
    private void rejectAudits() {
        jdbc.sql("CREATE FUNCTION reject_alias_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'PAYER_ALIAS_%' THEN RAISE EXCEPTION 'Fictional audit rejection'; END IF; RETURN NEW; END $$").update();
        jdbc.sql("CREATE TRIGGER reject_alias_audit_fixture BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_alias_audit_fixture()").update();
    }
    private void allowAudits() {
        jdbc.sql("DROP TRIGGER reject_alias_audit_fixture ON audit_event").update();
        jdbc.sql("DROP FUNCTION reject_alias_audit_fixture()").update();
    }
    private List<Object> race(Callable<Object> firstCall, Callable<Object> secondCall) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.Stream.of(firstCall, secondCall).map(call -> pool.submit(() -> {
                start.await(); try { return call.call(); } catch (PayerAliases.Conflict conflict) { return conflict; }
            })).toList();
            start.countDown(); return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        }
    }
}
