ALTER TABLE customer ADD CONSTRAINT customer_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE invoice (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    customer_id uuid NOT NULL,
    invoice_number varchar(100) NOT NULL CHECK (length(btrim(invoice_number)) > 0 AND invoice_number !~ '[[:cntrl:]]'),
    issue_date date NOT NULL CHECK (issue_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    due_date date NOT NULL CHECK (due_date BETWEEN issue_date AND DATE '9999-12-31'),
    amount_jpy bigint NOT NULL CHECK (amount_jpy BETWEEN 1 AND 9007199254740991),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    source_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, invoice_number),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id),
    FOREIGN KEY (tenant_id, source_batch_id) REFERENCES import_batch(tenant_id, id),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX invoice_receivables_page ON invoice(tenant_id, status, due_date, id);

CREATE TABLE invoice_revision (
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    operation varchar(16) NOT NULL CHECK (operation IN ('CREATED', 'CORRECTED', 'CANCELLED')),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    reason varchar(1000),
    actor_user_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id, revision),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_user_id) REFERENCES app_user(tenant_id, id),
    CHECK ((operation = 'CREATED' AND revision = 0 AND reason IS NULL)
        OR (operation <> 'CREATED' AND revision > 0 AND reason IS NOT NULL AND length(btrim(reason)) > 0))
);
