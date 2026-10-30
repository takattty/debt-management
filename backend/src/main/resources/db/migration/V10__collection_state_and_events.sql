-- Shared internal balance projection: public receivable/payment endpoints are unchanged.
CREATE VIEW reconciliation_invoice_balance AS
SELECT i.*,COALESCE(a.cash,0) AS active_cash_jpy,COALESCE(a.fee,0) AS active_fee_jpy
FROM invoice i LEFT JOIN LATERAL (
    SELECT sum(l.cash_jpy) AS cash,sum(l.fee_jpy) AS fee FROM allocation_line l
    JOIN allocation_operation o ON o.tenant_id=l.tenant_id AND o.id=l.operation_id
    WHERE l.tenant_id=i.tenant_id AND l.invoice_id=i.id AND o.kind='CONFIRM'
    AND NOT EXISTS (SELECT 1 FROM allocation_operation r WHERE r.tenant_id=o.tenant_id AND r.reverses_id=o.id)
) a ON true;

CREATE TABLE collection_state (
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    held boolean NOT NULL DEFAULT false,
    hold_reason varchar(1000),
    latest_note varchar(5000),
    next_action_date date CHECK (next_action_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    PRIMARY KEY (tenant_id,invoice_id),
    FOREIGN KEY (tenant_id,invoice_id) REFERENCES invoice(tenant_id,id),
    CHECK ((held AND hold_reason IS NOT NULL AND hold_reason ~ '[^[:space:]]') OR (NOT held AND hold_reason IS NULL)),
    CHECK (latest_note IS NULL OR latest_note ~ '[^[:space:]]')
);

CREATE TABLE collection_event (
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    kind varchar(16) NOT NULL CHECK (kind IN ('NOTE','HOLD','RELEASE')),
    note varchar(5000),
    reason varchar(1000),
    actor_user_id uuid NOT NULL,
    actor_label text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    held boolean NOT NULL,
    hold_reason varchar(1000),
    next_action_date date CHECK (next_action_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    PRIMARY KEY (tenant_id,invoice_id,version),
    FOREIGN KEY (tenant_id,invoice_id) REFERENCES collection_state(tenant_id,invoice_id),
    FOREIGN KEY (tenant_id,actor_user_id) REFERENCES app_user(tenant_id,id),
    CHECK ((held AND hold_reason IS NOT NULL AND hold_reason ~ '[^[:space:]]') OR (NOT held AND hold_reason IS NULL)),
    CHECK (note IS NULL OR note ~ '[^[:space:]]'),
    CHECK ((kind='NOTE' AND note IS NOT NULL AND reason IS NULL)
        OR (kind='HOLD' AND held AND reason IS NOT NULL AND reason=hold_reason AND reason ~ '[^[:space:]]')
        OR (kind='RELEASE' AND NOT held AND reason IS NOT NULL AND reason ~ '[^[:space:]]'))
);

CREATE FUNCTION protect_collection_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Collection state cannot be deleted' USING ERRCODE='23514';
    ELSIF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.held OR NEW.hold_reason IS NOT NULL OR NEW.latest_note IS NOT NULL OR NEW.next_action_date IS NOT NULL THEN
            RAISE EXCEPTION 'Collection state starts at empty version zero' USING ERRCODE='23514';
        END IF;
    ELSIF ROW(NEW.tenant_id,NEW.invoice_id) IS DISTINCT FROM ROW(OLD.tenant_id,OLD.invoice_id) OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION 'Collection state must advance one version without moving its invoice' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER collection_state_history BEFORE INSERT OR UPDATE OR DELETE ON collection_state
    FOR EACH ROW EXECUTE FUNCTION protect_collection_state();
CREATE TRIGGER collection_event_immutable BEFORE UPDATE OR DELETE ON collection_event
    FOR EACH ROW EXECUTE FUNCTION protect_allocation_history();

CREATE FUNCTION protect_collection_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.action LIKE 'COLLECTION_%' OR (TG_OP='UPDATE' AND NEW.action LIKE 'COLLECTION_%') THEN
        RAISE EXCEPTION 'Collection audits are append-only' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER collection_audit_immutable BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION protect_collection_audit();

CREATE FUNCTION identify_collection_actor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT email_canonical INTO NEW.actor_label FROM app_user
        WHERE tenant_id=NEW.tenant_id AND id=NEW.actor_user_id AND enabled FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Collection actor must be enabled in this tenant' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER collection_actor_label BEFORE INSERT ON collection_event
    FOR EACH ROW EXECUTE FUNCTION identify_collection_actor();

CREATE FUNCTION assert_collection_state(target_tenant uuid,target_invoice uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    state collection_state%ROWTYPE;
    latest collection_event%ROWTYPE;
    total bigint;
BEGIN
    SELECT * INTO STRICT state FROM collection_state WHERE tenant_id=target_tenant AND invoice_id=target_invoice FOR UPDATE;
    SELECT count(*) INTO total FROM collection_event WHERE tenant_id=target_tenant AND invoice_id=target_invoice;
    IF total<>state.version THEN
        RAISE EXCEPTION 'Collection state and event versions must agree' USING ERRCODE='23514';
    END IF;
    IF state.version>0 THEN
        SELECT * INTO latest FROM collection_event WHERE tenant_id=target_tenant AND invoice_id=target_invoice AND version=state.version;
        IF NOT FOUND OR ROW(state.held,state.hold_reason,state.latest_note,state.next_action_date)
            IS DISTINCT FROM ROW(latest.held,latest.hold_reason,latest.note,latest.next_action_date) THEN
            RAISE EXCEPTION 'Collection state must match its last immutable event' USING ERRCODE='23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION check_collection_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_collection_state(NEW.tenant_id,NEW.invoice_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER collection_state_events AFTER INSERT OR UPDATE ON collection_state
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_collection_state();

CREATE FUNCTION check_collection_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous collection_event%ROWTYPE;
BEGIN
    SELECT * INTO previous FROM collection_event
        WHERE tenant_id=NEW.tenant_id AND invoice_id=NEW.invoice_id AND version=NEW.version-1;
    IF NEW.version>1 AND NOT FOUND THEN
        RAISE EXCEPTION 'Collection event versions cannot have gaps' USING ERRCODE='23514';
    END IF;
    IF (NEW.kind='NOTE' AND ROW(NEW.held,NEW.hold_reason) IS DISTINCT FROM ROW(COALESCE(previous.held,false),previous.hold_reason))
        OR (NEW.kind='HOLD' AND COALESCE(previous.held,false))
        OR (NEW.kind='RELEASE' AND NOT COALESCE(previous.held,false))
        OR (NEW.kind<>'NOTE' AND ROW(NEW.note,NEW.next_action_date) IS DISTINCT FROM ROW(previous.note,previous.next_action_date)) THEN
        RAISE EXCEPTION 'Collection event cannot silently change another follow-up decision' USING ERRCODE='23514';
    END IF;
    PERFORM assert_collection_state(NEW.tenant_id,NEW.invoice_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER collection_event_state AFTER INSERT ON collection_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_collection_event();
