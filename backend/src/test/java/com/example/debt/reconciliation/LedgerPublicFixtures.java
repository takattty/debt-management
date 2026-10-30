package com.example.debt.reconciliation;

import com.example.debt.access.TenantUser;
import com.example.debt.access.UserRole;
import com.example.debt.invoice.InvoiceMutationService;
import com.example.debt.invoice.InvoiceQueries;
import com.example.debt.invoice.ReceivablesService;
import com.example.debt.payment.PaymentsService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
abstract class LedgerPublicFixtures {
    @Autowired JdbcClient jdbc;
    @Autowired LedgerRepository ledger;
    @Autowired PaymentsService payments;
    @Autowired ReceivablesService receivables;
    @Autowired InvoiceQueries invoices;
    @Autowired InvoiceMutationService mutations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    final UUID tenant = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    TenantUser user;
    TenantUser foreign;
    UUID customer;
    UUID foreignCustomer;
    UUID invoiceBatch;
    UUID paymentBatch;
    UUID foreignInvoiceBatch;
    UUID foreignPaymentBatch;
    UUID invoice;
    UUID payment;

    @BeforeEach
    void ledgerPublicFacts() {
        user = user(tenant); foreign = user(other);
        customer = customer(tenant); foreignCustomer = customer(other);
        invoiceBatch = batch(user, "INVOICE"); paymentBatch = batch(user, "PAYMENT");
        foreignInvoiceBatch = batch(foreign, "INVOICE"); foreignPaymentBatch = batch(foreign, "PAYMENT");
        invoice = invoice(user, 100000); payment = payment(user, 120000);
    }

    @AfterEach
    void removeDedicatedDatabaseLedgerAndOwnedFacts() {
        // Immutable tables are reset only in this suite's dedicated test database.
        jdbc.sql("TRUNCATE allocation_line,allocation_operation,reconciliation_payment_state,payment").update();
        for (UUID scope : List.of(tenant, other)) {
            for (String table : List.of("audit_event", "invoice_revision", "invoice", "customer", "import_batch", "app_user", "tenant")) {
                jdbc.sql("DELETE FROM " + table + " WHERE " + (table.equals("tenant") ? "id" : "tenant_id") + "=?").param(scope).update();
            }
        }
    }

    UUID allocate(UUID receipt, UUID target, long cash, long fee) {
        return tx().execute(status -> append(receipt, target, cash, fee));
    }
    UUID append(UUID receipt, UUID target, long cash, long fee) {
        var locked = ledger.lockPositions(tenant, receipt, List.of(target));
        UUID operation = header(receipt, locked.payment().version() + 1, "CONFIRM", null);
        jdbc.sql("INSERT INTO allocation_line VALUES (?,?,?,?,?,?)")
                .params(tenant, operation, target, locked.invoices().getFirst().version(), cash, fee).update();
        jdbc.sql("UPDATE invoice SET version=version+1 WHERE tenant_id=? AND id=?").params(tenant, target).update();
        return operation;
    }
    void reverse(UUID receipt, UUID target, UUID original) {
        tx().executeWithoutResult(status -> {
            var locked = ledger.lockPositions(tenant, receipt, List.of(target));
            header(receipt, locked.payment().version() + 1, "REVERSE", original);
            jdbc.sql("UPDATE invoice SET version=version+1 WHERE tenant_id=? AND id=?").params(tenant, target).update();
        });
    }
    UUID header(UUID receipt, long version, String kind, UUID original) {
        UUID id = UUID.randomUUID();
        jdbc.sql("UPDATE reconciliation_payment_state SET version=? WHERE tenant_id=? AND payment_id=?").params(version, tenant, receipt).update();
        jdbc.sql("INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(id, tenant, receipt, UUID.randomUUID(), kind, original, version, user.userId(), "Fictional internal ledger fixture").update();
        return id;
    }
    TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    RequestPostProcessor auth(TenantUser owner) {
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.authorities()));
    }
    TenantUser user(UUID scope) {
        UUID id = UUID.randomUUID(); String email = id + "@example.test";
        jdbc.sql("INSERT INTO tenant(id,name) VALUES (?,'Fictional ledger public read tenant')").param(scope).update();
        jdbc.sql("INSERT INTO app_user(id,tenant_id,email_canonical,password_hash,role) VALUES (?,?,?,'$argon2id$sql-fixture','ADMIN')")
                .params(id, scope, email).update();
        return new TenantUser(id, scope, email, UserRole.ADMIN);
    }
    UUID customer(UUID scope) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer(id,tenant_id,code,name) VALUES (?,?,?,'Fictional ledger customer')").params(id, scope, id.toString()).update();
        return id;
    }
    UUID batch(TenantUser owner, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,version,created_by_user_id,
                    created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count)
                VALUES (?,?,?,1,decode(?,'hex'),'CONFIRMED',1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day',?,CURRENT_TIMESTAMP,1,0)
                """).params(id, owner.tenantId(), kind, "c".repeat(64), owner.userId(), owner.userId()).update();
        return id;
    }
    UUID invoice(TenantUser owner, long amount) {
        UUID id = UUID.randomUUID(); boolean own = owner.tenantId().equals(tenant);
        jdbc.sql("INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id) VALUES (?,?,?,?,'0001-01-01','9999-12-31',?,?)")
                .params(id, owner.tenantId(), own ? customer : foreignCustomer, id.toString(), amount, own ? invoiceBatch : foreignInvoiceBatch).update();
        var state = invoices.get(owner.tenantId(), id, false);
        jdbc.sql("INSERT INTO invoice_revision(tenant_id,invoice_id,revision,operation,snapshot,actor_user_id,occurred_at) VALUES (?,?,0,'CREATED',?::jsonb,?,CURRENT_TIMESTAMP)")
                .params(owner.tenantId(), id, JsonMapper.builder().build().writeValueAsString(state.facts()), owner.userId()).update();
        return id;
    }
    UUID payment(TenantUser owner, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) VALUES (?,?,?,'0001-01-01','Fictional receipt',?,?,?,CURRENT_TIMESTAMP)")
                .params(id, owner.tenantId(), id.toString(), amount, owner.tenantId().equals(tenant) ? paymentBatch : foreignPaymentBatch, owner.userId()).update();
        return id;
    }
    long version() { return invoices.get(tenant, invoice, false).version(); }
    void correct(long expected, long amount) {
        mutations.correct(user, invoice, expected, customer, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), amount, "Fictional correction");
    }
    String trace() {
        StringBuilder text = new StringBuilder();
        for (String table : List.of("payment", "invoice", "invoice_revision", "reconciliation_payment_state", "allocation_operation", "allocation_line", "audit_event")) {
            text.append(jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM " + table + " t WHERE tenant_id=?")
                    .param(tenant).query(String.class).single());
        }
        return text.toString();
    }
}
