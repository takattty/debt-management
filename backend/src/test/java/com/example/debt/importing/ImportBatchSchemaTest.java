package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("auth-test")
class ImportBatchSchemaTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID alphaActor = UUID.randomUUID();
    private final UUID betaActor = UUID.randomUUID();
    private final String fingerprint = "a".repeat(64);
    private int rowIndex;
    private int errorIndex;

    @BeforeEach
    void insertDedicatedSqlFixturesWithoutAuthenticating() {
        for (UUID tenant : List.of(alpha, beta)) {
            jdbc.sql("INSERT INTO tenant(id,name) VALUES (?, 'Fictional import fixture')").param(tenant).update();
        }
        user(alphaActor, alpha);
        user(betaActor, beta);
    }

    @AfterEach
    void removeOnlyThisTestsTenantsAndDependents() {
        for (UUID tenant : List.of(alpha, beta)) {
            for (String table : List.of("import_error", "import_row", "import_batch", "app_user", "tenant")) {
                String column = table.equals("tenant") ? "id" : "tenant_id";
                jdbc.sql("DELETE FROM " + table + " WHERE " + column + "=?").param(tenant).update();
            }
        }
    }

    @Test
    void rejectsInvalidCountersExpiryStatusAndCrossTenantActors() {
        UUID batch = preview(alpha, alphaActor);
        for (String update : List.of("kind='OTHER'", "format_version=0", "version=-1", "row_count=10001", "error_count=-1",
                "semantic_fingerprint=decode('00','hex')", "expires_at=created_at", "status='UNKNOWN'",
                "status='PREVIEW_INVALID'", "error_count=1", "confirmed_by_user_id='" + alphaActor + "'",
                "created_by_user_id='" + betaActor + "'",
                "status='CONFIRMED',version=1,confirmed_by_user_id=created_by_user_id,confirmed_at=expires_at",
                "status='CONFIRMED',version=0,confirmed_by_user_id=created_by_user_id,confirmed_at=created_at",
                "status='CONFIRMED',version=1,confirmed_by_user_id='" + betaActor + "',confirmed_at=created_at")) {
            assertThatThrownBy(() -> update(batch, update)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.sql("SELECT status FROM import_batch WHERE tenant_id=? AND id=?")
                .params(alpha, batch).query(String.class).single()).isEqualTo("PREVIEW_VALID");
    }

    @Test
    void constrainsChildTenantReferencesJsonObjectsAndErrorDetails() {
        UUID batch = preview(alpha, alphaActor);
        row(alpha, batch, "{}", "{}");
        assertThatThrownBy(() -> row(beta, batch, "{}", "{}")).isInstanceOf(DataIntegrityViolationException.class);
        for (String invalid : List.of("[]", "null", "1", "\"text\"")) {
            assertThatThrownBy(() -> row(alpha, batch, invalid, "{}")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> row(alpha, batch, "{}", invalid)).isInstanceOf(DataIntegrityViolationException.class);
        }
        update(batch, "status='PREVIEW_INVALID',error_count=1");
        error(alpha, batch, 2, "amount");
        assertThatThrownBy(() -> error(beta, batch, 2, "amount")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> error(alpha, batch, 0, "amount")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> error(alpha, batch, 2, " ")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE import_error SET input_value=? WHERE tenant_id=? AND batch_id=?")
                .params("𠮷".repeat(200), alpha, batch).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE import_error SET input_value=? WHERE tenant_id=? AND batch_id=?")
                .params("𠮷".repeat(201), alpha, batch).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT typed_snapshot='{}'::jsonb FROM import_row WHERE tenant_id=? AND batch_id=?")
                .params(alpha, batch).query(Boolean.class).single()).isTrue();
    }

    @Test
    void concurrentConfirmationOfTwoPreviewsWithSameFingerprintHasExactlyOneWinner() throws Exception {
        UUID first = preview(alpha, alphaActor);
        UUID second = preview(alpha, alphaActor);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrentConfirm(first, ready, start));
            var b = executor.submit(() -> concurrentConfirm(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM import_batch WHERE tenant_id=? AND status='CONFIRMED'")
                .param(alpha).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM import_batch WHERE tenant_id=? AND status='PREVIEW_VALID'")
                .param(alpha).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void confirmedFingerprintIsScopedToTenantKindAndFormatVersion() {
        confirm(preview(alpha, alphaActor));
        UUID invoice = preview(alpha, alphaActor);
        update(invoice, "kind='INVOICE'");
        confirm(invoice);
        UUID nextFormat = preview(alpha, alphaActor);
        update(nextFormat, "format_version=2");
        confirm(nextFormat);
        UUID otherTenant = preview(beta, betaActor);
        jdbc.sql("UPDATE import_batch SET status='CONFIRMED',version=1,confirmed_at=created_at,confirmed_by_user_id=? WHERE tenant_id=? AND id=?")
                .params(betaActor, beta, otherTenant).update();
        assertThat(jdbc.sql("SELECT count(*) FROM import_batch WHERE tenant_id IN (?,?) AND status='CONFIRMED'")
                .params(alpha, beta).query(Long.class).single()).isEqualTo(4);
    }

    private boolean concurrentConfirm(UUID batch, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ready.countDown(); // Both physical connections exist before either transaction proceeds.
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Import fixture start timed out");
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE import_batch SET status='CONFIRMED',version=1,
                        confirmed_at=created_at,confirmed_by_user_id=created_by_user_id WHERE tenant_id=? AND id=?
                    """)) {
                statement.setQueryTimeout(10);
                statement.setObject(1, alpha);
                statement.setObject(2, batch);
                assertThat(statement.executeUpdate()).isEqualTo(1);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private void user(UUID id, UUID tenant) {
        jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,'$argon2id$sql-fixture','ADMIN')")
                .params(id, tenant, id + "@example.test").update();
    }

    private UUID preview(UUID tenant, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,
                    created_by_user_id,created_at,expires_at,row_count,error_count)
                VALUES (?,?,'CUSTOMER',1,decode(?,'hex'),'PREVIEW_VALID',?,'2026-10-29T00:00:00Z','2026-10-30T00:00:00Z',1,0)
                """).params(id, tenant, fingerprint, actor).update();
        return id;
    }

    private void confirm(UUID batch) {
        update(batch, "status='CONFIRMED',version=1,confirmed_at=created_at,confirmed_by_user_id=created_by_user_id");
    }

    private void update(UUID batch, String assignments) {
        jdbc.sql("UPDATE import_batch SET " + assignments + " WHERE tenant_id=? AND id=?").params(alpha, batch).update();
    }

    private void row(UUID tenant, UUID batch, String payload, String references) {
        jdbc.sql("INSERT INTO import_row VALUES (?,?,?,2,?::jsonb,?::jsonb)")
                .params(tenant, batch, ++rowIndex, payload, references).update();
    }

    private void error(UUID tenant, UUID batch, int line, String field) {
        jdbc.sql("INSERT INTO import_error VALUES (?,?,?,?,?, 'abc',false,'INVALID','Invalid fixture','Correct the value')")
                .params(tenant, batch, ++errorIndex, line, field).update();
    }
}
