CREATE TABLE payer_alias (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    payer_name varchar(200) COLLATE "C" NOT NULL CHECK (length(btrim(payer_name)) > 0 AND payer_name !~ '[[:cntrl:]]'),
    customer_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deactivated_by_user_id uuid,
    deactivated_at timestamptz,
    deactivation_reason varchar(500),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by_user_id) REFERENCES app_user(tenant_id, id),
    FOREIGN KEY (tenant_id, deactivated_by_user_id) REFERENCES app_user(tenant_id, id),
    CHECK ((active AND version=0 AND deactivated_by_user_id IS NULL AND deactivated_at IS NULL AND deactivation_reason IS NULL)
        OR (NOT active AND version=1 AND deactivated_by_user_id IS NOT NULL AND deactivated_at IS NOT NULL
            AND deactivation_reason IS NOT NULL AND length(btrim(deactivation_reason)) > 0
            AND deactivation_reason !~ '[[:cntrl:]]' AND deactivated_at >= created_at))
);
CREATE UNIQUE INDEX payer_alias_active_pair ON payer_alias(tenant_id, payer_name, customer_id) WHERE active;
CREATE INDEX payer_alias_active_page ON payer_alias(tenant_id, id) WHERE active;

CREATE FUNCTION protect_payer_alias_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payer alias history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NOT OLD.active OR NEW.active OR NEW.version <> OLD.version + 1
        OR ROW(NEW.id, NEW.tenant_id, NEW.payer_name, NEW.customer_id, NEW.created_by_user_id, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.tenant_id, OLD.payer_name, OLD.customer_id, OLD.created_by_user_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Only one-way payer alias deactivation is allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payer_alias_history BEFORE UPDATE OR DELETE ON payer_alias
    FOR EACH ROW EXECUTE FUNCTION protect_payer_alias_history();
