package com.example.debt.invoice;

import com.example.debt.access.TenantUser;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InvoiceMutationService {
    private final JdbcClient jdbc;
    private final InvoiceQueries queries;
    private final InvoiceMutationEligibility eligibility;
    private final JsonMapper json = JsonMapper.builder().build();

    public InvoiceMutationService(JdbcClient jdbc, InvoiceQueries queries, InvoiceMutationEligibility eligibility) {
        this.jdbc = jdbc; this.queries = queries; this.eligibility = eligibility;
    }

    @Transactional
    public InvoiceState correct(TenantUser user, UUID id, long version, UUID customerId, LocalDate issueDate,
                                LocalDate dueDate, long amount, String rawReason) {
        String reason = reason(rawReason);
        InvoiceState previous = mutable(user, id, version);
        InvoiceValues values = new InvoiceValues(customerId, previous.invoiceNumber(), issueDate, dueDate, amount);
        if (jdbc.sql("SELECT id FROM customer WHERE tenant_id=? AND id=? FOR SHARE").params(user.tenantId(), values.customerId())
                .query(UUID.class).optional().isEmpty()) {
            throw new InvoiceValidationException("customerId", "CUSTOMER_NOT_FOUND", "Specify a customer in this tenant");
        }
        jdbc.sql("""
                UPDATE invoice SET customer_id=:customer,issue_date=:issue,due_date=:due,amount_jpy=:amount,
                    version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant AND id=:id AND version=:version
                """).param("customer", values.customerId()).param("issue", values.issueDate()).param("due", values.dueDate())
                .param("amount", values.amountJpy()).param("tenant", user.tenantId()).param("id", id).param("version", version).update();
        InvoiceState updated = queries.get(user.tenantId(), id, false);
        record(user, previous, updated, "CORRECTED", reason);
        return updated;
    }

    private InvoiceState mutable(TenantUser user, UUID id, long version) {
        if (jdbc.sql("SELECT id FROM app_user WHERE tenant_id=? AND id=? AND enabled FOR SHARE")
                .params(user.tenantId(), user.userId()).query(UUID.class).optional().isEmpty()) {
            throw new AccessDeniedException("User membership changed");
        }
        InvoiceState invoice = queries.get(user.tenantId(), id, true);
        if (version < 0 || invoice.version() != version) { throw new InvoiceConflict("VERSION_CONFLICT"); }
        if (!invoice.status().equals("ACTIVE")) { throw new InvoiceConflict("INVOICE_NOT_ACTIVE"); }
        eligibility.requireUnallocated(user.tenantId(), id);
        return invoice;
    }

    private String reason(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().codePointCount(0, raw.strip().length()) > 1000
                || raw.codePoints().anyMatch(point -> point == 0 || point >= 0xd800 && point <= 0xdfff)) {
            throw new InvoiceValidationException("reason", "INVALID_REASON", "A reason of 1 through 1000 code points is required");
        }
        return raw.strip();
    }

    private void record(TenantUser user, InvoiceState before, InvoiceState after, String operation, String reason) {
        jdbc.sql("""
                INSERT INTO invoice_revision(tenant_id,invoice_id,revision,operation,snapshot,reason,actor_user_id,occurred_at)
                VALUES (?,?,?,?,CAST(? AS jsonb),?,?,CURRENT_TIMESTAMP)
                """).params(user.tenantId(), after.id(), after.version(), operation, json.writeValueAsString(after.facts()), reason, user.userId()).update();
        jdbc.sql("""
                INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb))
                """).params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(), "INVOICE_" + operation, after.id(),
                        json.writeValueAsString(Map.of("before", before.facts(), "after", after.facts(), "reason", reason))).update();
    }
}
