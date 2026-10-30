package com.example.debt.payment;

import com.example.debt.access.TenantUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PayerAliases {
    private final JdbcClient jdbc;
    public record Row(UUID id, String payerName, UUID customerId, boolean active, long version,
                      UUID createdByUserId, Instant createdAt, UUID deactivatedByUserId,
                      Instant deactivatedAt, String deactivationReason) { }
    public record Page(List<Row> items, UUID nextCursor) { }
    public static class Missing extends RuntimeException { }
    public static class Conflict extends RuntimeException {
        public Conflict(String code) { super(code); }
    }

    public PayerAliases(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page list(UUID tenant, UUID cursor, int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid alias page size"); }
        var rows = jdbc.sql("SELECT * FROM payer_alias WHERE tenant_id=:tenant AND active"
                + (cursor == null ? "" : " AND id>:cursor") + " ORDER BY id LIMIT :limit")
                .params(cursor == null ? Map.of("tenant", tenant, "limit", limit + 1)
                        : Map.of("tenant", tenant, "limit", limit + 1, "cursor", cursor)).query(this::row).list();
        var page = rows.stream().limit(limit).toList();
        return new Page(page, rows.size() > limit ? page.getLast().id() : null);
    }

    public Row get(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM payer_alias WHERE tenant_id=? AND id=?").params(tenant, id)
                .query(this::row).optional().orElseThrow(Missing::new);
    }

    @Transactional
    public Row register(TenantUser user, String payerName, UUID customer) {
        String name = PaymentValues.text(payerName, 200);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM customer WHERE tenant_id=? AND id=?)")
                .params(user.tenantId(), customer).query(Boolean.class).single()) { throw new Missing(); }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO payer_alias(id,tenant_id,payer_name,customer_id,created_by_user_id) VALUES (?,?,?,?,?)")
                    .params(id, user.tenantId(), name, customer, user.userId()).update();
        } catch (DuplicateKeyException duplicate) { throw new Conflict("PAYER_ALIAS_EXISTS"); }
        Row result = get(user.tenantId(), id);
        audit(user, result, "PAYER_ALIAS_REGISTERED");
        return result;
    }

    @Transactional
    public Row deactivate(TenantUser user, UUID id, long version, String reason) {
        String explanation = PaymentValues.text(reason, 500);
        if (version < 0) { throw new IllegalArgumentException("Invalid alias version"); }
        int count = jdbc.sql("""
                UPDATE payer_alias SET active=false,version=version+1,deactivated_by_user_id=:actor,
                    deactivated_at=CURRENT_TIMESTAMP,deactivation_reason=:reason
                WHERE tenant_id=:tenant AND id=:id AND active AND version=:version
                """).params(Map.of("actor", user.userId(), "reason", explanation, "tenant", user.tenantId(),
                        "id", id, "version", version)).update();
        if (count == 0) { get(user.tenantId(), id); throw new Conflict("VERSION_CONFLICT"); }
        Row result = get(user.tenantId(), id);
        audit(user, result, "PAYER_ALIAS_DEACTIVATED");
        return result;
    }

    private void audit(TenantUser user, Row row, String action) {
        String details = JsonMapper.builder().build().writeValueAsString(Map.of(
                "payerName", row.payerName(), "customerId", row.customerId(), "version", row.version(),
                "active", row.active(), "reason", row.deactivationReason() == null ? "" : row.deactivationReason()));
        jdbc.sql("INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))")
                .params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(), action, row.id(), details).update();
    }

    private Row row(java.sql.ResultSet result, int index) throws java.sql.SQLException {
        var deactivated = result.getTimestamp("deactivated_at");
        return new Row(result.getObject("id", UUID.class), result.getString("payer_name"), result.getObject("customer_id", UUID.class),
                result.getBoolean("active"), result.getLong("version"), result.getObject("created_by_user_id", UUID.class),
                result.getTimestamp("created_at").toInstant(), result.getObject("deactivated_by_user_id", UUID.class),
                deactivated == null ? null : deactivated.toInstant(), result.getString("deactivation_reason"));
    }
}
