package com.example.debt.collection;

import com.example.debt.access.TenantUser;
import com.example.debt.payment.PaymentValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CollectionActions {
    private final JdbcClient jdbc;
    private final CollectionQueries queries;
    private final JsonMapper json = JsonMapper.builder().build();
    public static class Conflict extends RuntimeException {
        public Conflict(String code) { super(code); }
    }
    private record State(long version, boolean held, String holdReason, String note, LocalDate nextDate) { }
    public CollectionActions(JdbcClient jdbc, CollectionQueries queries) { this.jdbc = jdbc; this.queries = queries; }

    @Transactional
    public CollectionQueries.Event record(TenantUser user, UUID invoice, long version, String note, LocalDate nextDate) {
        String text = CollectionValues.note(note);
        if (nextDate != null) { CollectionValues.date(nextDate); }
        return update(user, invoice, version, "NOTE", text, nextDate, null);
    }
    @Transactional
    public CollectionQueries.Event hold(TenantUser user, UUID invoice, long version, String reason) {
        return update(user, invoice, version, "HOLD", null, null, PaymentValues.text(reason, 1000));
    }
    @Transactional
    public CollectionQueries.Event release(TenantUser user, UUID invoice, long version, String reason) {
        return update(user, invoice, version, "RELEASE", null, null, PaymentValues.text(reason, 1000));
    }

    private CollectionQueries.Event update(TenantUser user, UUID invoice, long expectedVersion, String kind, String note, LocalDate nextDate, String reason) {
        if (expectedVersion < 0) { throw new IllegalArgumentException("Invalid collection version"); }
        if (jdbc.sql("SELECT id FROM app_user WHERE tenant_id=? AND id=? AND enabled FOR SHARE")
                .params(user.tenantId(), user.userId()).query(UUID.class).optional().isEmpty()) { throw new AccessDeniedException("Enabled tenant membership is required"); }
        if (jdbc.sql("SELECT id FROM invoice WHERE tenant_id=? AND id=? FOR KEY SHARE").params(user.tenantId(), invoice)
                .query(UUID.class).optional().isEmpty()) { throw new CollectionQueries.Missing(); }
        jdbc.sql("INSERT INTO collection_state(tenant_id,invoice_id) VALUES (?,?) ON CONFLICT DO NOTHING").params(user.tenantId(), invoice).update();
        var previous = jdbc.sql("SELECT * FROM collection_state WHERE tenant_id=? AND invoice_id=? FOR UPDATE").params(user.tenantId(), invoice)
                .query((result, row) -> new State(result.getLong("version"), result.getBoolean("held"), result.getString("hold_reason"),
                        result.getString("latest_note"), result.getObject("next_action_date", LocalDate.class))).single();
        if (previous.version() != expectedVersion) { throw new Conflict("VERSION_CONFLICT"); }
        if (kind.equals("HOLD") && previous.held()) { throw new Conflict("COLLECTION_ALREADY_HELD"); }
        if (kind.equals("RELEASE") && !previous.held()) { throw new Conflict("COLLECTION_NOT_HELD"); }
        long version = Math.addExact(previous.version(), 1);
        boolean held = kind.equals("NOTE") ? previous.held() : kind.equals("HOLD");
        String holdReason = kind.equals("NOTE") ? previous.holdReason() : held ? reason : null;
        String latestNote = kind.equals("NOTE") ? note : previous.note();
        LocalDate planned = kind.equals("NOTE") ? nextDate : previous.nextDate();
        jdbc.sql("UPDATE collection_state SET version=?,held=?,hold_reason=?,latest_note=?,next_action_date=? WHERE tenant_id=? AND invoice_id=? AND version=?")
                .params(version, held, holdReason, latestNote, planned, user.tenantId(), invoice, expectedVersion).update();
        jdbc.sql("INSERT INTO collection_event(tenant_id,invoice_id,version,kind,note,reason,actor_user_id,held,hold_reason,next_action_date) VALUES (?,?,?,?,?,?,?,?,?,?)")
                .params(user.tenantId(), invoice, version, kind, latestNote, reason, user.userId(), held, holdReason, planned).update();
        var details = new LinkedHashMap<String, Object>();
        details.put("version", version); details.put("kind", kind); details.put("note", latestNote);
        details.put("reason", reason); details.put("held", held); details.put("holdReason", holdReason); details.put("nextActionDate", planned);
        jdbc.sql("INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))")
                .params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(), "COLLECTION_" + kind, invoice,
                        json.writeValueAsString(details)).update();
        return queries.history(user.tenantId(), invoice, expectedVersion, 1).items().getFirst();
    }
}
