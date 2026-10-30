ALTER TABLE import_batch DROP CONSTRAINT import_batch_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_kind_check CHECK (kind IN ('CUSTOMER', 'INVOICE', 'PAYMENT'));
ALTER TABLE import_batch ADD CONSTRAINT import_batch_tenant_id_kind UNIQUE (tenant_id, id, kind);

CREATE TABLE payment (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    transaction_id varchar(100) NOT NULL CHECK (length(btrim(transaction_id)) > 0 AND transaction_id !~ '[[:cntrl:]]'),
    transfer_date date NOT NULL CHECK (transfer_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    payer_name varchar(200) NOT NULL CHECK (length(btrim(payer_name)) > 0 AND payer_name !~ '[[:cntrl:]]'),
    amount_jpy bigint NOT NULL CHECK (amount_jpy BETWEEN 1 AND 9007199254740991),
    source_batch_id uuid NOT NULL,
    source_kind varchar(16) NOT NULL DEFAULT 'PAYMENT' CHECK (source_kind = 'PAYMENT'),
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, transaction_id),
    FOREIGN KEY (tenant_id, source_batch_id, source_kind) REFERENCES import_batch(tenant_id, id, kind),
    FOREIGN KEY (tenant_id, created_by_user_id) REFERENCES app_user(tenant_id, id)
);
CREATE INDEX payment_page ON payment(tenant_id, transfer_date, id);

CREATE FUNCTION reject_payment_fact_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Payment facts are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER payment_facts_immutable BEFORE UPDATE OR DELETE ON payment
    FOR EACH ROW EXECUTE FUNCTION reject_payment_fact_change();
