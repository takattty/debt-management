package com.example.debt.reconciliation;

import com.example.debt.access.TenantUser;
import com.example.debt.payment.PaymentValues;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AllocationCommands {
    public record Line(UUID invoiceId, long version, long cash, long fee) { }
    public record Operation(UUID id, UUID paymentId, UUID requestId, String kind, UUID reversesId,
                            long paymentVersion, UUID actorUserId, String reason, Instant occurredAt) { }
    public static class Missing extends RuntimeException { }
    public static class Conflict extends RuntimeException {
        public Conflict(String code) { super(code); }
    }
    private final JdbcClient jdbc;
    private final LedgerRepository ledger;
    private final JsonMapper json = JsonMapper.builder().build();

    public AllocationCommands(JdbcClient jdbc, LedgerRepository ledger) { this.jdbc = jdbc; this.ledger = ledger; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Operation confirm(TenantUser user, UUID payment, UUID request, long paymentVersion, String rawReason, List<Line> input) {
        String reason = PaymentValues.text(rawReason, 1000);
        List<Line> lines = validate(input);
        BigInteger totalCash = lines.stream().map(line -> BigInteger.valueOf(line.cash())).reduce(BigInteger.ZERO, BigInteger::add);
        if (totalCash.signum() <= 0 || lines.stream().anyMatch(line -> line.cash() == 0 && line.fee() == 0)) {
            throw new IllegalArgumentException("A confirmation requires positive cash and nonempty lines");
        }
        String fingerprint = fingerprint(user, payment, "CONFIRM", null, paymentVersion, reason, lines);
        Operation replay = begin(user, request, fingerprint);
        if (replay != null) { return replay; }
        var locked = lock(user.tenantId(), payment, lines);
        versions(locked, paymentVersion, lines);
        if (totalCash.compareTo(locked.payment().balance().unallocated()) > 0) { throw new Conflict("BALANCE_CONFLICT"); }
        for (var position : locked.invoices()) {
            Line line = lines.stream().filter(item -> item.invoiceId().equals(position.id())).findFirst().orElseThrow();
            if (!position.status().equals("ACTIVE")) { throw new Conflict("INVOICE_NOT_ACTIVE"); }
            if (BigInteger.valueOf(line.cash()).add(BigInteger.valueOf(line.fee())).compareTo(position.balance().outstanding()) > 0) {
                throw new Conflict("BALANCE_CONFLICT");
            }
        }
        UUID id = append(user, payment, request, "CONFIRM", null, next(paymentVersion), reason, fingerprint);
        for (Line line : lines) {
            jdbc.sql("INSERT INTO allocation_line(tenant_id,operation_id,invoice_id,invoice_version,cash_jpy,fee_jpy) VALUES (?,?,?,?,?,?)")
                    .params(user.tenantId(), id, line.invoiceId(), line.version(), line.cash(), line.fee()).update();
        }
        advanceInvoices(user.tenantId(), lines);
        return recorded(user, id, lines);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Operation reverse(TenantUser user, UUID payment, UUID original, UUID request, long paymentVersion,
                             String rawReason, List<Line> input) {
        String reason = PaymentValues.text(rawReason, 1000);
        List<Line> lines = validate(input);
        if (lines.stream().anyMatch(line -> line.cash() != 0 || line.fee() != 0)) { throw new IllegalArgumentException("Unexpected reversal amount"); }
        String fingerprint = fingerprint(user, payment, "REVERSE", original, paymentVersion, reason, lines);
        Operation replay = begin(user, request, fingerprint);
        if (replay != null) { return replay; }
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM allocation_operation WHERE tenant_id=? AND payment_id=? AND id=? AND kind='CONFIRM')")
                .params(user.tenantId(), payment, original).query(Boolean.class).single()) { throw new Missing(); }
        List<UUID> originalIds = jdbc.sql("SELECT invoice_id FROM allocation_line WHERE tenant_id=? AND operation_id=?")
                .params(user.tenantId(), original).query(UUID.class).list();
        if (!originalIds.stream().sorted().toList().equals(lines.stream().map(Line::invoiceId).toList())) {
            throw new IllegalArgumentException("Reversal requires every original invoice exactly once");
        }
        var locked = lock(user.tenantId(), payment, lines);
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM allocation_operation WHERE tenant_id=? AND payment_id=? AND reverses_id=?)")
                .params(user.tenantId(), payment, original).query(Boolean.class).single()) { throw new Conflict("ALLOCATION_ALREADY_REVERSED"); }
        versions(locked, paymentVersion, lines);
        UUID id = append(user, payment, request, "REVERSE", original, next(paymentVersion), reason, fingerprint);
        advanceInvoices(user.tenantId(), lines);
        return recorded(user, id, lines);
    }

    private List<Line> validate(List<Line> input) {
        if (input == null || input.isEmpty() || input.size() > 100 || input.stream().anyMatch(line -> line == null
                || line.invoiceId() == null || line.version() < 0 || line.cash() < 0 || line.fee() < 0
                || line.cash() > PaymentValues.MAX_AMOUNT_JPY || line.fee() > PaymentValues.MAX_AMOUNT_JPY)) {
            throw new IllegalArgumentException("Invalid allocation lines");
        }
        if (input.stream().map(Line::invoiceId).distinct().count() != input.size()) { throw new IllegalArgumentException("Duplicate invoice"); }
        return input.stream().sorted(Comparator.comparing(Line::invoiceId)).toList();
    }

    private Operation begin(TenantUser user, UUID request, String fingerprint) {
        if (request == null) { throw new IllegalArgumentException("Request ID is required"); }
        if (jdbc.sql("SELECT id FROM app_user WHERE tenant_id=? AND id=? AND enabled AND role IN ('ADMIN','ACCOUNTANT') FOR SHARE")
                .params(user.tenantId(), user.userId()).query(UUID.class).optional().isEmpty()) { throw new AccessDeniedException("User membership changed"); }
        // Namespace and request hashes can collide only by causing harmless extra serialization.
        jdbc.sql("SELECT pg_advisory_xact_lock(?,?)").params(user.tenantId().hashCode(), request.hashCode()).query((rs, row) -> true).single();
        var existing = jdbc.sql("SELECT request_fingerprint FROM allocation_operation WHERE tenant_id=? AND request_id=?")
                .params(user.tenantId(), request).query((rs, row) -> rs.getString(1)).list();
        if (existing.isEmpty()) { return null; }
        if (!fingerprint.equals(existing.getFirst())) { throw new Conflict("IDEMPOTENCY_CONFLICT"); }
        UUID id = jdbc.sql("SELECT id FROM allocation_operation WHERE tenant_id=? AND request_id=?")
                .params(user.tenantId(), request).query(UUID.class).single();
        return get(user.tenantId(), id);
    }

    private LedgerRepository.LockedPositions lock(UUID tenant, UUID payment, List<Line> lines) {
        try { return ledger.lockPositions(tenant, payment, lines.stream().map(Line::invoiceId).toList()); }
        catch (IllegalArgumentException absent) { throw new Missing(); }
    }

    private void versions(LedgerRepository.LockedPositions locked, long paymentVersion, List<Line> lines) {
        if (paymentVersion < 0 || locked.payment().version() != paymentVersion) { throw new Conflict("VERSION_CONFLICT"); }
        next(paymentVersion);
        for (var invoice : locked.invoices()) {
            if (lines.stream().noneMatch(line -> line.invoiceId().equals(invoice.id()) && line.version() == invoice.version())) {
                throw new Conflict("VERSION_CONFLICT");
            }
            next(invoice.version());
        }
    }
    private long next(long version) {
        try { return Math.incrementExact(version); }
        catch (ArithmeticException exhausted) { throw new Conflict("VERSION_EXHAUSTED"); }
    }
    private UUID append(TenantUser user, UUID payment, UUID request, String kind, UUID original, long version,
                        String reason, String fingerprint) {
        UUID id = UUID.randomUUID();
        jdbc.sql("UPDATE reconciliation_payment_state SET version=? WHERE tenant_id=? AND payment_id=?")
                .params(version, user.tenantId(), payment).update();
        jdbc.sql("""
                INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason,request_fingerprint)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """).params(id, user.tenantId(), payment, request, kind, original, version, user.userId(), reason, fingerprint).update();
        return id;
    }
    private void advanceInvoices(UUID tenant, List<Line> lines) {
        for (Line line : lines) {
            jdbc.sql("UPDATE invoice SET version=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND version=?")
                    .params(next(line.version()), tenant, line.invoiceId(), line.version()).update();
        }
    }
    private Operation recorded(TenantUser user, UUID id, List<Line> lines) {
        Operation result = get(user.tenantId(), id);
        jdbc.sql("INSERT INTO audit_event(id,tenant_id,actor,actor_user_id,action,entity_id,details) VALUES (?,?,?,?,?,?,?::jsonb)")
                .params(UUID.randomUUID(), user.tenantId(), "user:" + user.userId(), user.userId(),
                        result.kind().equals("CONFIRM") ? "ALLOCATION_CONFIRMED" : "ALLOCATION_REVERSED", id,
                        json.writeValueAsString(Map.of("operation", result, "observedInvoices", lines))).update();
        return result;
    }
    private Operation get(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM allocation_operation WHERE tenant_id=? AND id=?").params(tenant, id)
                .query((rs, row) -> new Operation(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                        rs.getObject("request_id", UUID.class), rs.getString("kind"), rs.getObject("reverses_id", UUID.class),
                        rs.getLong("payment_version"), rs.getObject("actor_user_id", UUID.class), rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant())).single();
    }
    private String fingerprint(TenantUser user, UUID payment, String kind, UUID original, long version, String reason, List<Line> lines) {
        String canonical = json.writeValueAsString(List.of("allocation-v1", user.userId(), payment, kind,
                original == null ? "" : original, version, reason, lines));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
