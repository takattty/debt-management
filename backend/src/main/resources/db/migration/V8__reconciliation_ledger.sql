CREATE TABLE reconciliation_payment_state (
    tenant_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, payment_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES payment(tenant_id, id)
);

CREATE TABLE allocation_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    request_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('CONFIRM', 'REVERSE')),
    reverses_id uuid,
    payment_version bigint NOT NULL CHECK (payment_version > 0),
    actor_user_id uuid NOT NULL,
    reason varchar(1000) NOT NULL CHECK (reason ~ '[^[:space:]]'),
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_xid xid8 NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, payment_id, id),
    UNIQUE (tenant_id, request_id),
    UNIQUE (tenant_id, payment_id, payment_version),
    UNIQUE (tenant_id, reverses_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES reconciliation_payment_state(tenant_id, payment_id),
    FOREIGN KEY (tenant_id, actor_user_id) REFERENCES app_user(tenant_id, id),
    FOREIGN KEY (tenant_id, payment_id, reverses_id) REFERENCES allocation_operation(tenant_id, payment_id, id),
    CHECK ((kind = 'CONFIRM' AND reverses_id IS NULL) OR (kind = 'REVERSE' AND reverses_id IS NOT NULL))
);

CREATE TABLE allocation_line (
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    invoice_version bigint NOT NULL CHECK (invoice_version >= 0),
    cash_jpy numeric NOT NULL CHECK (cash_jpy >= 0 AND cash_jpy = trunc(cash_jpy)
        AND cash_jpy NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    fee_jpy numeric NOT NULL CHECK (fee_jpy >= 0 AND fee_jpy = trunc(fee_jpy)
        AND fee_jpy NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    PRIMARY KEY (tenant_id, operation_id, invoice_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES allocation_operation(tenant_id, id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id),
    CHECK (cash_jpy + fee_jpy > 0)
);
CREATE INDEX allocation_line_invoice ON allocation_line(tenant_id, invoice_id);

CREATE FUNCTION protect_allocation_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Allocation history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER allocation_operation_immutable BEFORE UPDATE OR DELETE ON allocation_operation
    FOR EACH ROW EXECUTE FUNCTION protect_allocation_history();
CREATE TRIGGER allocation_line_immutable BEFORE UPDATE OR DELETE ON allocation_line
    FOR EACH ROW EXECUTE FUNCTION protect_allocation_history();

CREATE FUNCTION protect_reconciliation_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR ROW(NEW.tenant_id, NEW.payment_id) IS DISTINCT FROM ROW(OLD.tenant_id, OLD.payment_id)
        OR NEW.version < OLD.version THEN
        RAISE EXCEPTION 'Reconciliation position cannot be deleted, moved or rewound' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reconciliation_state_history BEFORE UPDATE OR DELETE ON reconciliation_payment_state
    FOR EACH ROW EXECUTE FUNCTION protect_reconciliation_state();

CREATE FUNCTION initialize_allocation_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_xid := pg_current_xact_id();
    IF NEW.kind = 'REVERSE' AND NOT EXISTS (SELECT 1 FROM allocation_operation
        WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND id=NEW.reverses_id AND kind='CONFIRM') THEN
        RAISE EXCEPTION 'A reversal must reference an original confirmation in the same tenant and payment' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER allocation_operation_origin BEFORE INSERT ON allocation_operation
    FOR EACH ROW EXECUTE FUNCTION initialize_allocation_operation();

CREATE FUNCTION protect_allocation_line_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM allocation_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id
        AND kind='CONFIRM' AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'Lines must be appended with their original confirmation transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER allocation_line_origin BEFORE INSERT ON allocation_line
    FOR EACH ROW EXECUTE FUNCTION protect_allocation_line_origin();

CREATE FUNCTION assert_reconciliation_payment(target_tenant uuid, target_payment uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    position_version bigint;
    last_version bigint;
    operation_count bigint;
    payment_amount numeric;
    active_cash numeric;
BEGIN
    SELECT version INTO position_version FROM reconciliation_payment_state
        WHERE tenant_id=target_tenant AND payment_id=target_payment FOR UPDATE;
    SELECT amount_jpy INTO STRICT payment_amount FROM payment WHERE tenant_id=target_tenant AND id=target_payment;
    SELECT COALESCE(max(payment_version),0), count(*) INTO last_version, operation_count
        FROM allocation_operation WHERE tenant_id=target_tenant AND payment_id=target_payment;
    IF position_version IS NULL OR position_version <> last_version OR operation_count <> last_version THEN
        RAISE EXCEPTION 'Payment position and append-only versions must agree without gaps' USING ERRCODE = '23514';
    END IF;
    SELECT COALESCE(sum(l.cash_jpy),0) INTO active_cash FROM allocation_operation o
        JOIN allocation_line l ON l.tenant_id=o.tenant_id AND l.operation_id=o.id
        WHERE o.tenant_id=target_tenant AND o.payment_id=target_payment AND o.kind='CONFIRM'
        AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id);
    IF active_cash > payment_amount THEN
        RAISE EXCEPTION 'Active cash exceeds payment amount' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION assert_reconciliation_invoice(target_tenant uuid, target_invoice uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    invoice_amount numeric;
    invoice_status varchar;
    active_total numeric;
BEGIN
    SELECT amount_jpy,status INTO STRICT invoice_amount,invoice_status FROM invoice
        WHERE tenant_id=target_tenant AND id=target_invoice FOR UPDATE;
    SELECT COALESCE(sum(l.cash_jpy + l.fee_jpy),0) INTO active_total FROM allocation_line l
        JOIN allocation_operation o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
        WHERE l.tenant_id=target_tenant AND l.invoice_id=target_invoice AND o.kind='CONFIRM'
        AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id);
    IF active_total > invoice_amount OR (invoice_status <> 'ACTIVE' AND active_total > 0) THEN
        RAISE EXCEPTION 'Active allocations require an active invoice and nonnegative outstanding amount' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION check_allocation_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation allocation_operation%ROWTYPE;
    source_operation uuid;
    target_invoice uuid;
BEGIN
    IF TG_TABLE_NAME='allocation_line' THEN
        source_operation := NEW.operation_id;
    ELSE
        source_operation := NEW.id;
    END IF;
    SELECT * INTO STRICT operation FROM allocation_operation
        WHERE tenant_id=NEW.tenant_id AND id=source_operation;
    PERFORM assert_reconciliation_payment(operation.tenant_id,operation.payment_id);
    source_operation := CASE WHEN operation.kind='REVERSE' THEN operation.reverses_id ELSE operation.id END;
    IF NOT EXISTS (SELECT 1 FROM allocation_line WHERE tenant_id=operation.tenant_id AND operation_id=source_operation) THEN
        RAISE EXCEPTION 'A confirmation must have at least one line' USING ERRCODE = '23514';
    END IF;
    FOR target_invoice IN SELECT invoice_id FROM allocation_line
        WHERE tenant_id=operation.tenant_id AND operation_id=source_operation ORDER BY invoice_id LOOP
        PERFORM assert_reconciliation_invoice(operation.tenant_id,target_invoice);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER allocation_operation_balances AFTER INSERT ON allocation_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_allocation_operation();
CREATE CONSTRAINT TRIGGER allocation_line_balances AFTER INSERT ON allocation_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_allocation_operation();

CREATE FUNCTION check_reconciliation_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_reconciliation_payment(NEW.tenant_id,NEW.payment_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER reconciliation_state_balances AFTER INSERT OR UPDATE ON reconciliation_payment_state
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_reconciliation_state();

CREATE FUNCTION check_invoice_reconciliation_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_reconciliation_invoice(NEW.tenant_id,NEW.id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER invoice_reconciliation_balance AFTER UPDATE OF amount_jpy,status ON invoice
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_invoice_reconciliation_balance();
