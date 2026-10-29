CREATE TABLE import_batch (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('CUSTOMER', 'INVOICE')),
    format_version integer NOT NULL CHECK (format_version > 0),
    semantic_fingerprint bytea NOT NULL CHECK (octet_length(semantic_fingerprint) = 32),
    status varchar(20) NOT NULL CHECK (status IN ('PREVIEW_VALID', 'PREVIEW_INVALID', 'CONFIRMED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    confirmed_by_user_id uuid,
    confirmed_at timestamptz,
    row_count integer NOT NULL CHECK (row_count BETWEEN 0 AND 10000),
    error_count integer NOT NULL CHECK (error_count >= 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by_user_id) REFERENCES app_user(tenant_id, id),
    FOREIGN KEY (tenant_id, confirmed_by_user_id) REFERENCES app_user(tenant_id, id),
    CHECK (expires_at > created_at),
    CHECK ((status = 'PREVIEW_INVALID') = (error_count > 0)),
    CHECK ((status = 'CONFIRMED' AND confirmed_by_user_id IS NOT NULL AND confirmed_at IS NOT NULL AND version > 0)
        OR (status <> 'CONFIRMED' AND confirmed_by_user_id IS NULL AND confirmed_at IS NULL)),
    CHECK (confirmed_at IS NULL OR (confirmed_at >= created_at AND confirmed_at < expires_at))
);
CREATE UNIQUE INDEX import_batch_confirmed_fingerprint
    ON import_batch(tenant_id, kind, format_version, semantic_fingerprint) WHERE status = 'CONFIRMED';

CREATE TABLE import_row (
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    record_number integer NOT NULL CHECK (record_number BETWEEN 1 AND 10000),
    physical_start_line integer NOT NULL CHECK (physical_start_line > 0),
    typed_snapshot jsonb NOT NULL CHECK (jsonb_typeof(typed_snapshot) = 'object'),
    reference_snapshot jsonb NOT NULL CHECK (jsonb_typeof(reference_snapshot) = 'object'),
    PRIMARY KEY (tenant_id, batch_id, record_number),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batch(tenant_id, id)
);

CREATE TABLE import_error (
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    error_number integer NOT NULL CHECK (error_number > 0),
    row_number integer NOT NULL CHECK (row_number > 0),
    field varchar(100) NOT NULL CHECK (length(btrim(field)) > 0),
    input_value varchar(200) NOT NULL,
    input_truncated boolean NOT NULL,
    reason_code varchar(100) NOT NULL CHECK (length(btrim(reason_code)) > 0),
    reason varchar(1000) NOT NULL CHECK (length(btrim(reason)) > 0),
    correction varchar(1000) NOT NULL CHECK (length(btrim(correction)) > 0),
    PRIMARY KEY (tenant_id, batch_id, error_number),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batch(tenant_id, id)
);
